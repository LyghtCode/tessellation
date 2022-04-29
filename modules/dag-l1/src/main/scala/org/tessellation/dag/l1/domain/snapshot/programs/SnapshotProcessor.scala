package org.tessellation.dag.l1.domain.snapshot.programs

import cats.effect.Async
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.{Applicative, MonadThrow}

import scala.util.control.NoStackTrace

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.block.BlockStorage.MajorityReconciliationData
import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor._
import org.tessellation.dag.l1.domain.snapshot.storage.LastGlobalSnapshotStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.snapshot._
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.height.Height
import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.types.numeric.NonNegLong

object SnapshotProcessor {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F],
    lastGlobalSnapshotStorage: LastGlobalSnapshotStorage[F],
    transactionStorage: TransactionStorage[F]
  ): SnapshotProcessor[F] =
    new SnapshotProcessor[F](addressStorage, blockStorage, lastGlobalSnapshotStorage, transactionStorage) {}

  sealed trait Alignment
  case class AlignedAtNewOrdinal(toMarkMajority: Set[(ProofsHash, NonNegLong)]) extends Alignment
  case class AlignedAtNewHeight(toMarkMajority: Set[(ProofsHash, NonNegLong)], obsoleteToRemove: Set[ProofsHash])
      extends Alignment
  case class DownloadNeeded(toAdd: Set[(Hashed[DAGBlock], NonNegLong)], obsoleteToRemove: Set[ProofsHash])
      extends Alignment
  case class RedownloadNeeded(
    toAdd: Set[(Hashed[DAGBlock], NonNegLong)],
    toMarkMajority: Set[(ProofsHash, NonNegLong)],
    acceptedToRemove: Set[ProofsHash],
    obsoleteToRemove: Set[ProofsHash],
    toReset: Set[ProofsHash]
  ) extends Alignment

  sealed trait SnapshotProcessingResult
  case class Aligned(
    height: Height,
    ordinal: SnapshotOrdinal,
    hash: Hash,
    proofsHash: ProofsHash,
    removedObsoleteBlocks: Set[ProofsHash]
  ) extends SnapshotProcessingResult
  case class DownloadPerformed(
    height: Height,
    ordinal: SnapshotOrdinal,
    hash: Hash,
    proofsHash: ProofsHash,
    addedBlock: Set[ProofsHash],
    removedObsoleteBlocks: Set[ProofsHash]
  ) extends SnapshotProcessingResult
  case class RedownloadPerformed(
    height: Height,
    ordinal: SnapshotOrdinal,
    hash: Hash,
    proofsHash: ProofsHash,
    addedBlocks: Set[ProofsHash],
    removedBlocks: Set[ProofsHash],
    removedObsoleteBlocks: Set[ProofsHash]
  ) extends SnapshotProcessingResult

  sealed trait SnapshotProcessingError extends NoStackTrace
  case class UnexpectedCaseCheckingAlignment(
    lastHeight: Height,
    lastOrdinal: SnapshotOrdinal,
    processingHeight: Height,
    processingOrdinal: SnapshotOrdinal
  ) extends SnapshotProcessingError {
    override def getMessage: String =
      s"Unexpected case during global snapshot processing! Last: (height: $lastHeight, ordinal: $lastOrdinal) processing: (height: $processingHeight, ordinal: $processingOrdinal)."
  }
}

sealed abstract class SnapshotProcessor[F[_]: Async: KryoSerializer: SecurityProvider] private (
  addressStorage: AddressStorage[F],
  blockStorage: BlockStorage[F],
  lastGlobalSnapshotStorage: LastGlobalSnapshotStorage[F],
  transactionStorage: TransactionStorage[F]
) {

  def process(globalSnapshot: Hashed[GlobalSnapshot]): F[SnapshotProcessingResult] =
    checkAlignment(globalSnapshot).flatMap {
      case AlignedAtNewOrdinal(toMarkMajority) =>
        blockStorage
          .adjustToMajority(toMarkMajority = toMarkMajority)
          .flatMap(_ => lastGlobalSnapshotStorage.set(globalSnapshot))
          .map(
            _ =>
              Aligned(
                globalSnapshot.height,
                globalSnapshot.ordinal,
                globalSnapshot.hash,
                globalSnapshot.proofsHash,
                Set.empty
              )
          )

      case AlignedAtNewHeight(toMarkMajority, obsoleteToRemove) =>
        blockStorage
          .adjustToMajority(toMarkMajority = toMarkMajority, obsoleteToRemove = obsoleteToRemove)
          .flatMap(_ => lastGlobalSnapshotStorage.set(globalSnapshot))
          .map(
            _ =>
              Aligned(
                globalSnapshot.height,
                globalSnapshot.ordinal,
                globalSnapshot.hash,
                globalSnapshot.proofsHash,
                obsoleteToRemove
              )
          )

      case DownloadNeeded(toAdd, obsoleteToRemove) =>
        val adjustToMajority: F[Unit] =
          blockStorage.adjustToMajority(toAdd = toAdd, obsoleteToRemove = obsoleteToRemove)

        val setBalances: F[Unit] =
          addressStorage.clean >>
            addressStorage.updateBalances(globalSnapshot.info.balances)

        val setTransactionRefs: F[Unit] =
          transactionStorage.setLastAccepted(globalSnapshot.info.lastTxRefs)

        val setInitialSnapshot: F[Unit] =
          lastGlobalSnapshotStorage.setInitial(globalSnapshot)

        adjustToMajority >>
          setBalances >>
          setTransactionRefs >>
          setInitialSnapshot
            .map(
              _ =>
                DownloadPerformed(
                  globalSnapshot.height,
                  globalSnapshot.ordinal,
                  globalSnapshot.hash,
                  globalSnapshot.proofsHash,
                  toAdd.map(_._1.proofsHash),
                  obsoleteToRemove
                )
            )

      case RedownloadNeeded(toAdd, toMarkMajority, acceptedToRemove, obsoleteToRemove, toReset) =>
        val adjustToMajority: F[Unit] =
          blockStorage.adjustToMajority(
            toAdd = toAdd,
            toMarkMajority = toMarkMajority,
            acceptedToRemove = acceptedToRemove,
            obsoleteToRemove = obsoleteToRemove,
            toReset = toReset
          )

        val setBalances: F[Unit] =
          addressStorage.clean >>
            addressStorage.updateBalances(globalSnapshot.info.balances)

        val setTransactionRefs: F[Unit] =
          transactionStorage.setLastAccepted(globalSnapshot.info.lastTxRefs)

        val setSnapshot: F[Unit] =
          lastGlobalSnapshotStorage.set(globalSnapshot)

        adjustToMajority >>
          setBalances >>
          setTransactionRefs >>
          setSnapshot
            .map(
              _ =>
                RedownloadPerformed(
                  globalSnapshot.height,
                  globalSnapshot.ordinal,
                  globalSnapshot.hash,
                  globalSnapshot.proofsHash,
                  toAdd.map(_._1.proofsHash),
                  acceptedToRemove,
                  obsoleteToRemove
                )
            )
    }

  private def checkAlignment(globalSnapshot: GlobalSnapshot): F[Alignment] =
    for {
      acceptedInMajority <- globalSnapshot.blocks.toList.traverse {
        case BlockAsActiveTip(block, usageCount) =>
          block.hashWithSignatureCheck.flatMap(_.liftTo[F]).map(b => b.proofsHash -> (b, usageCount))
      }.map(_.toMap)

      GlobalSnapshotTips(gsDeprecatedTips, gsRemainedActive) = globalSnapshot.tips

      result <- lastGlobalSnapshotStorage.get.flatMap {
        case Some(last) if last.ordinal.next == globalSnapshot.ordinal && last.height == globalSnapshot.height =>
          blockStorage.getBlocksForMajorityReconciliation(last.height, globalSnapshot.height).flatMap {
            case MajorityReconciliationData(_, _, _, _, acceptedAbove) =>
              val onlyInMajority = acceptedInMajority -- acceptedAbove
              val toMarkMajority = acceptedInMajority.view.filterKeys(acceptedAbove.contains).mapValues(_._2)
              lazy val toAdd = onlyInMajority.values.toSet
              lazy val toReset = acceptedAbove -- toMarkMajority.keySet
//              val deprecatedTipsToRemove = deprecatedTips -- gsDeprecatedTips.map(_.block.hash)
//              val deprecatedTipsToAdd = gsDeprecatedTips.filterNot(d => deprecatedTips.contains(d.block.hash))

              if (onlyInMajority.isEmpty)
                Applicative[F].pure[Alignment](AlignedAtNewOrdinal(toMarkMajority.toSet))
              else
                Applicative[F]
                  .pure[Alignment](RedownloadNeeded(toAdd, toMarkMajority.toSet, Set.empty, Set.empty, toReset))
          }

        case Some(last)
            if last.ordinal.next == globalSnapshot.ordinal && last.height.value < globalSnapshot.height.value =>
          blockStorage.getBlocksForMajorityReconciliation(last.height, globalSnapshot.height).flatMap {
            case MajorityReconciliationData(_, _, waitingInRange, acceptedInRange, acceptedAbove) =>
              val acceptedLocally = acceptedInRange ++ acceptedAbove
              val onlyInMajority = acceptedInMajority -- acceptedLocally
              val toMarkMajority = acceptedInMajority.view.filterKeys(acceptedLocally.contains).mapValues(_._2)
              val acceptedToRemove = acceptedInRange -- acceptedInMajority.keySet
              lazy val toAdd = onlyInMajority.values.toSet
              lazy val toReset = acceptedLocally -- toMarkMajority.keySet -- acceptedToRemove
              val obsoleteToRemove = waitingInRange -- onlyInMajority.keySet

              if (onlyInMajority.isEmpty && acceptedToRemove.isEmpty)
                Applicative[F].pure[Alignment](AlignedAtNewHeight(toMarkMajority.toSet, obsoleteToRemove))
              else
                Applicative[F].pure[Alignment](
                  RedownloadNeeded(toAdd, toMarkMajority.toSet, acceptedToRemove, obsoleteToRemove, toReset)
                )
          }

        case Some(last) =>
          MonadThrow[F].raiseError[Alignment](
            UnexpectedCaseCheckingAlignment(last.height, last.ordinal, globalSnapshot.height, globalSnapshot.ordinal)
          )

        case None =>
          blockStorage.getBlocksForMajorityReconciliation(Height.MinValue, globalSnapshot.height).flatMap {
            case MajorityReconciliationData(_, _, waitingInRange, _, _) =>
              val obsoleteToRemove = waitingInRange -- acceptedInMajority.keySet

              Applicative[F].pure[Alignment](DownloadNeeded(acceptedInMajority.values.toSet, obsoleteToRemove))
          }
      }
    } yield result
}
