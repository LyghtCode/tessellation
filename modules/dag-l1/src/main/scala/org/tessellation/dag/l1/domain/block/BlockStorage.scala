package org.tessellation.dag.l1.domain.block

import cats.Show
import cats.data.NonEmptyList
import cats.effect.Sync
import cats.effect.std.Random
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.traverse._

import scala.util.control.NoStackTrace

import org.tessellation.dag.domain.block.{BlockReference, DAGBlock, Tips}
import org.tessellation.dag.l1.domain.block.BlockStorage._
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.ext.collection.MapRefUtils.MapRefOps
import org.tessellation.schema.height.Height
import org.tessellation.security.Hashed
import org.tessellation.security.hash.ProofsHash
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import io.chrisdavenport.mapref.MapRef
import io.estatico.newtype.ops.toCoercibleIdOps
import monocle.macros.syntax.lens._

class BlockStorage[F[_]: Sync: Random](blocks: MapRef[F, ProofsHash, Option[StoredBlock]]) {

  implicit val showStoredBlock: Show[StoredBlock] = {
    case _: WaitingBlock  => "Waiting"
    case _: AcceptedBlock => "Accepted"
    case _: MajorityBlock => "Majority"
  }

  def areParentsAccepted(block: DAGBlock): F[Map[BlockReference, Boolean]] =
    block.parent.traverse { ref =>
      isBlockAccepted(ref).map(ref -> _)
    }.map(_.toList.toMap)

  private[block] def accept(hashedBlock: Hashed[DAGBlock]): F[Unit] =
    blocks(hashedBlock.proofsHash).modify {
      case Some(_: WaitingBlock) => (AcceptedBlock(hashedBlock).some, ().asRight)
      case other                 => (other, BlockAcceptanceError(hashedBlock.proofsHash, other).asLeft)
    }.flatMap(_.liftTo[F])
      .flatMap(_ => addParentUsages(hashedBlock))

  def adjustToMajority(
    toAdd: Set[(Hashed[DAGBlock], NonNegLong)] = Set.empty,
    toMarkMajority: Set[(ProofsHash, NonNegLong)] = Set.empty,
    acceptedToRemove: Set[ProofsHash] = Set.empty,
    obsoleteToRemove: Set[ProofsHash] = Set.empty,
    toReset: Set[ProofsHash] = Set.empty
  ): F[Unit] = {
    val addMajorityBlocks = toAdd.toList.traverse {
      case (block, initialUsages) =>
        val reference = BlockReference(block.proofsHash, block.height)
        blocks(block.proofsHash).modify {
          case Some(WaitingBlock(_)) | None => (MajorityBlock(reference, initialUsages, Active).some, ().asRight)
          case other                        => (other, UnexpectedBlockStateWhenAddingMajorityBlock(block.proofsHash, other).asLeft)
        }.flatMap(_.liftTo[F])
    }.void

    val markMajorityBlocks = toMarkMajority.toList.traverse {
      case (hash, initialUsages) =>
        blocks(hash).modify {
          case Some(AcceptedBlock(block)) =>
            val reference = BlockReference(block.proofsHash, block.height)
            (MajorityBlock(reference, initialUsages, Active).some, ().asRight)
          case other =>
            (other, UnexpectedBlockStateWhenMarkingAsMajority(hash, other).asLeft)
        }.flatMap(_.liftTo[F])
    }.void

    val removeAcceptedNonMajorityBlocks = acceptedToRemove.toList.traverse { hash =>
      blocks(hash).modify {
        case Some(AcceptedBlock(block)) => (None, block.asRight)
        case other                      => (None, UnexpectedBlockStateWhenRemovingAccepted(hash, other).asLeft)
      }.flatMap(_.liftTo[F])
        .flatMap(hb => removeParentUsages(hb))
    }.void

    val removeObsoleteBlocks = obsoleteToRemove.toList.traverse { hash =>
      blocks(hash).modify {
        case Some(WaitingBlock(block)) => (None, ().asRight)
        case other                     => (other, UnexpectedBlockStateWhenRemoving(hash, other).asLeft)
      }.flatMap(_.liftTo[F])
    }

    val resetBlocks = toReset.toList.traverse { hash =>
      blocks(hash).modify {
        case Some(AcceptedBlock(block))  => (WaitingBlock(block.signed).some, ().asRight)
        case Some(waiting: WaitingBlock) => (waiting.some, ().asRight)
        case other                       => (None, UnexpectedBlockStateWhenResetting(hash, other).asLeft)
      }.flatMap(_.liftTo[F])
    }.void

    addMajorityBlocks >>
      markMajorityBlocks >>
      removeAcceptedNonMajorityBlocks >>
      removeObsoleteBlocks >>
      resetBlocks
  }

  def store(hashedBlock: Hashed[DAGBlock]): F[Unit] =
    blocks(hashedBlock.proofsHash).modify {
      case None  => (WaitingBlock(hashedBlock.signed).some, ().asRight)
      case other => (other, BlockAlreadyStoredError(hashedBlock.proofsHash, other).asLeft)
    }.flatMap(_.liftTo[F])

  def getWaiting: F[Map[ProofsHash, Signed[DAGBlock]]] =
    blocks.toMap.map(_.collect { case (hash, WaitingBlock(block)) => hash -> block })

  def getBlocksForMajorityReconciliation(lastHeight: Height, currentHeight: Height): F[MajorityReconciliationData] =
    for {
      all <- blocks.toMap
      range = lastHeight.next.value to currentHeight.value
      deprecatedTips = all.collect { case (hash, MajorityBlock(_, _, Deprecated)) => hash }.toSet
      activeTips = all.collect { case (hash, MajorityBlock(_, _, Active))         => hash }.toSet
//      belowNonMajority = all.filter {
//        case (_, WaitingBlock(block))    => block.height.value < lastHeight.value
//        case (_, AcceptedBlock(block))   => block.height.value < lastHeight.value
//        case (_, MajorityBlock(_, _, _)) => false
//      }
//      inRange = all.filter {
//        case (_, WaitingBlock(block))    => block.height.value >= lastClosedHeight.value
//        case (_, AcceptedBlock(block))   => block.height.value >= lastClosedHeight.value
//        case (_, MajorityBlock(_, _, _)) => false
//      }
      waitingInRange = all.collect { case (hash, WaitingBlock(b)) if range.contains(b.height.value)  => hash }.toSet
      acceptedInRange = all.collect { case (hash, WaitingBlock(b)) if range.contains(b.height.value) => hash }.toSet
      acceptedAbove = all.collect {
        case (hash, AcceptedBlock(block)) if block.height.value > currentHeight.value => hash
      }.toSet
    } yield MajorityReconciliationData(deprecatedTips, activeTips, waitingInRange, acceptedInRange, acceptedAbove)

  def getTips(tipsCount: PosInt): F[Option[Tips]] =
    blocks.toMap
      .map(_.collect { case (_, MajorityBlock(blockReference, _, Active)) => blockReference })
      .map(_.toList)
      .flatMap(Random[F].shuffleList)
      .map(_.sortBy(_.height.coerce))
      .map {
        case tips if tips.size >= tipsCount =>
          NonEmptyList
            .fromList(tips.take(tipsCount))
            .map(Tips(_))
        case _ => None
      }

  private def isBlockAccepted(blockReference: BlockReference): F[Boolean] =
    blocks(blockReference.hash).get.map(_.exists(_.isInstanceOf[MajorityBlock]))

  private def addParentUsages(hashedBlock: Hashed[DAGBlock]): F[Unit] = {
    hashedBlock.parent.toList.traverse { blockReference =>
      blocks(blockReference.hash).modify {
        case Some(majority: MajorityBlock) => (majority.addUsage.some, ().asRight)
        case other                         => (other, TipUsageUpdateError(hashedBlock.proofsHash, blockReference.hash, other).asLeft)
      }.flatMap(_.liftTo[F])
    }
  }.void

  private def removeParentUsages(hashedBlock: Hashed[DAGBlock]): F[Unit] = {
    hashedBlock.parent.toList.traverse { blockReference =>
      blocks(blockReference.hash).modify {
        case Some(majorityBlock: MajorityBlock) =>
          (majorityBlock.removeUsage.some, ().asRight)
        case other => (other, TipUsageUpdateError(hashedBlock.proofsHash, blockReference.hash, other).asLeft)
      }
    }
  }.void
}

object BlockStorage {

  def make[F[_]: Sync: Random]: F[BlockStorage[F]] =
    MapRef.ofConcurrentHashMap[F, ProofsHash, StoredBlock]().map(new BlockStorage[F](_))

  sealed trait StoredBlock
  case class WaitingBlock(block: Signed[DAGBlock]) extends StoredBlock
  case class AcceptedBlock(block: Hashed[DAGBlock]) extends StoredBlock
  case class MajorityBlock(blockReference: BlockReference, usages: NonNegLong, tipStatus: TipStatus)
      extends StoredBlock {
    def addUsage: MajorityBlock = this.focus(_.usages).modify(usages => NonNegLong.unsafeFrom(usages + 1L))

    def removeUsage: MajorityBlock =
      this.focus(_.usages).modify(usages => NonNegLong.from(usages - 1L).toOption.getOrElse(NonNegLong.MinValue))
  }

  case class MajorityReconciliationData(
    deprecatedTips: Set[ProofsHash],
    activeTips: Set[ProofsHash],
    waitingInRange: Set[ProofsHash],
    acceptedInRange: Set[ProofsHash],
    acceptedAbove: Set[ProofsHash]
  )

  sealed trait TipStatus
  case object Active extends TipStatus
  case object Deprecated extends TipStatus

  sealed trait BlockStorageError extends NoStackTrace {
    val errorMessage: String
    override def getMessage: String = errorMessage
  }
  case class TipUsageUpdateError(child: ProofsHash, parent: ProofsHash, encountered: Option[StoredBlock])(
    implicit s: Show[StoredBlock]
  ) extends BlockStorageError {

    val errorMessage: String =
      s"Parent block with hash=${parent.show} not found in majority when updating usage! Child hash=${child.show}. Encountered state: ${encountered.show}"
  }
  case class BlockAcceptanceError(hash: ProofsHash, encountered: Option[StoredBlock])(implicit s: Show[StoredBlock])
      extends BlockStorageError {

    val errorMessage: String =
      s"Block with hash=${hash.show} failed to transition state to Accepted! Encountered state: ${encountered.show}."
  }
  case class BlockAlreadyStoredError(hash: ProofsHash, encountered: Option[StoredBlock])(implicit s: Show[StoredBlock])
      extends BlockStorageError {

    val errorMessage: String =
      s"Block with hash=${hash.show} is already stored. Encountered state: ${encountered.show}."
  }
  sealed trait BlockMajorityUpdateError extends BlockStorageError
  case class UnexpectedBlockStateWhenMarkingAsMajority(hash: ProofsHash, got: Option[StoredBlock])
      extends BlockMajorityUpdateError {
    val errorMessage: String = s"Accepted block to be marked as majority with hash: $hash not found! But got: $got"
  }
  case class UnexpectedBlockStateWhenRemovingAccepted(hash: ProofsHash, got: Option[StoredBlock])
      extends BlockMajorityUpdateError {

    val errorMessage: String =
      s"Accepted block to be removed during majority update with hash: $hash not found! But got: $got"
  }
  case class UnexpectedBlockStateWhenRemoving(hash: ProofsHash, got: Option[StoredBlock])
      extends BlockMajorityUpdateError {

    val errorMessage: String =
      s"Block to be removed during majority update with hash: $hash not found in expected state! But got: $got"
  }
  case class UnexpectedBlockStateWhenResetting(hash: ProofsHash, got: Option[StoredBlock])
      extends BlockMajorityUpdateError {

    val errorMessage: String =
      s"Block to be reset during majority update with hash: $hash not found in expected state! But got: $got"
  }
  case class UnexpectedBlockStateWhenAddingMajorityBlock(hash: ProofsHash, got: Option[StoredBlock])
      extends BlockMajorityUpdateError {

    val errorMessage: String =
      s"Block to be added during majority update with hash: $hash not found in expected state! But got: $got"
  }
}
