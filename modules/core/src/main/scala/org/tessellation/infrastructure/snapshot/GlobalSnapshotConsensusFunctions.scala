package org.tessellation.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.bifunctor._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.list._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.{Applicative, Eval}

import org.tessellation.dag.domain.block.{BlockReference, DAGBlock}
import org.tessellation.dag.snapshot._
import org.tessellation.domain.snapshot._
import org.tessellation.domain.snapshot.rewards.Rewards
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.infrastructure.snapshot.processing.{
  BlockAcceptanceManager,
  BlockAcceptanceState,
  deprecationThreshold
}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.consensus.ConsensusFunctions
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalSnapshotConsensusFunctions[F[_]]
    extends ConsensusFunctions[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact] {}

object GlobalSnapshotConsensusFunctions {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    globalSnapshotStorage: GlobalSnapshotStorage[F],
    heightInterval: NonNegLong,
    blockAcceptanceManager: BlockAcceptanceManager[F]
  ): GlobalSnapshotConsensusFunctions[F] = new GlobalSnapshotConsensusFunctions[F] {

    private val logger = Slf4jLogger.getLoggerFromClass(GlobalSnapshotConsensusFunctions.getClass)

    def consumeSignedMajorityArtifact(signedArtifact: Signed[GlobalSnapshotArtifact]): F[Unit] =
      globalSnapshotStorage
        .prepend(signedArtifact)
        .ifM(Applicative[F].unit, logger.error("Cannot save GlobalSnapshot into the storage"))

    def triggerPredicate(
      last: (GlobalSnapshotKey, Signed[GlobalSnapshotArtifact]),
      event: GlobalSnapshotEvent
    ): Boolean = event.toOption.flatMap(_.toOption).fold(false) {
      case TipSnapshotTrigger(height) => last._2.value.height.nextN(heightInterval) === height
      case TimeSnapshotTrigger()      => true
    }

    def createProposalArtifact(
      last: (GlobalSnapshotKey, Signed[GlobalSnapshotArtifact]),
      events: Set[GlobalSnapshotEvent]
    ): F[(GlobalSnapshotArtifact, Set[GlobalSnapshotEvent])] = {
      val (_, lastGS) = last

      val scEvents = events.toList.mapFilter(_.swap.toOption)
      val dagEvents: Seq[DAGEvent] = events.toList.mapFilter(_.toOption)

      val blocksForAcceptance = dagEvents.mapFilter[Signed[DAGBlock]](_.swap.toOption).toList

      for {
        lastGSHash <- lastGS.hashF
        currentOrdinal = lastGS.ordinal.next

        (scSnapshots, returnedSCEvents) = processStateChannelEvents(lastGS.info, scEvents)
        sCSnapshotHashes <- scSnapshots.toList.traverse { case (address, nel) => nel.head.hashF.map(address -> _) }
          .map(_.toMap)
        lastStateChannelSnapshotHashes = lastGS.info.lastStateChannelSnapshotHashes ++ sCSnapshotHashes

        tipUsages <- getTipsUsages(lastGS)
        initState = BlockAcceptanceState(lastGS.info.lastTxRefs, lastGS.info.balances, tipUsages)
        acceptanceResult <- blockAcceptanceManager.processBlocks(initState, blocksForAcceptance)

        (newlyDeprecated, remainedActive) <- getActiveTipsWithUpdatedUsages(lastGS, acceptanceResult.state.tipUsages)
          .map(
            _.partition(_.usageCount >= deprecationThreshold)
              .bimap(_.map(at => DeprecatedTip(at.block, currentOrdinal)), identity)
          )

        lowestActiveIntroducedAt = remainedActive.map(_.introducedAt).minimumOption.getOrElse(currentOrdinal)
        (newlyRemoved, remainedDeprecated) = lastGS.tips.deprecated.toList
          .partition(_.deprecatedAt <= lowestActiveIntroducedAt)
        deprecated = newlyDeprecated ++ remainedDeprecated

        _ <- logger.debug(s"Tips removed: ${newlyRemoved.show}")

        height <- getMinHeight(deprecated, remainedActive, acceptanceResult.acceptedBlocks)
        subHeight <- if (height > lastGS.height) SubHeight.MinValue.pure[F]
        else if (height === lastGS.height) lastGS.subHeight.next.pure[F]
        else InvalidHeight(lastGS.height, height).raiseError

        rewards = Rewards.calculateRewards(lastGS.proofs.map(_.id))

        returnedDAGEvents = acceptanceResult.awaitingBlocks
          .map(_.asLeft[SnapshotTrigger].asRight[StateChannelEvent])

        globalSnapshot = GlobalSnapshot(
          currentOrdinal,
          height,
          subHeight,
          lastGSHash,
          acceptanceResult.acceptedBlocks.toSet,
          scSnapshots,
          rewards,
          NonEmptyList.of(PeerId(Hex("peer1"))), // TODO
          GlobalSnapshotInfo(
            lastStateChannelSnapshotHashes,
            acceptanceResult.state.lastTxRefs,
            acceptanceResult.state.balances
          ),
          GlobalSnapshotTips(
            deprecated = deprecated.toSet,
            remainedActive = remainedActive.toSet
          )
        )
        returnedEvents = returnedSCEvents.union(returnedDAGEvents.toSet)
      } yield (globalSnapshot, returnedEvents)
    }

    case class InvalidHeight(lastHeight: Height, currentHeight: Height) extends Throwable

    private def getTipsUsages(gs: GlobalSnapshot): F[Map[BlockReference, NonNegLong]] =
      gs.activeTips.map { activeTips =>
        val activeTipsUsages = activeTips.map(at => (at.block, at.usageCount)).toMap
        val deprecatedTipsUsages = gs.tips.deprecated.map(dt => (dt.block, deprecationThreshold)).toMap

        activeTipsUsages ++ deprecatedTipsUsages
      }

    private def getActiveTipsWithUpdatedUsages(
      gs: GlobalSnapshot,
      tipUsages: Map[BlockReference, NonNegLong]
    ): F[List[ActiveTip]] =
      gs.activeTips.flatMap { activeTips =>
        activeTips.toList.traverse { at =>
          tipUsages
            .get(at.block)
            .liftTo[F](new RuntimeException("Tip not found in TipUsages"))
            .map(usageCount => at.copy(usageCount = usageCount))
        }
      }

    def getMinHeight(
      deprecated: List[DeprecatedTip],
      remainedActive: List[ActiveTip],
      blocks: List[BlockAsActiveTip]
    ): F[Height] =
      (deprecated.map(_.block.height) ++ remainedActive.map(_.block.height) ++ blocks
        .map(_.block.height)).minimumOption
        .liftTo[F](new RuntimeException("Invalid snapshot attempt, no tips remaining"))

    private def processStateChannelEvents(
      lastGlobalSnapshotInfo: GlobalSnapshotInfo,
      events: List[StateChannelEvent]
    ): (Map[Address, NonEmptyList[StateChannelSnapshotBinary]], Set[GlobalSnapshotEvent]) = {
      val lshToSnapshot: Map[(Address, Hash), StateChannelEvent] = events.map { e =>
        (e.address, e.outputGist.lastSnapshotHash) -> e
      }.foldLeft(Map.empty[(Address, Hash), StateChannelEvent]) { (acc, entry) =>
        entry match {
          case (k, newEvent) =>
            acc.updatedWith(k) { maybeEvent =>
              maybeEvent
                .fold(newEvent) { event =>
                  if (Hash.fromBytes(event.outputBinary) < Hash.fromBytes(newEvent.outputBinary))
                    event
                  else
                    newEvent
                }
                .some
            }
        }
      }

      val result = events
        .map(_.address)
        .distinct
        .mapFilter { address =>
          lastGlobalSnapshotInfo.lastStateChannelSnapshotHashes
            .get(address)
            .map(hash => address -> hash)
        }
        .mapFilter {
          case (address, initLsh) =>
            def unfold(lsh: Hash): Eval[List[StateChannelEvent]] =
              lshToSnapshot
                .get((address, lsh))
                .map { go =>
                  for {
                    head <- Eval.now(go)
                    tail <- unfold(Hash.fromBytes(go.outputBinary))
                  } yield head :: tail
                }
                .getOrElse(Eval.now(List.empty))

            unfold(initLsh).value.toNel.map(
              nel =>
                address -> nel
                  .map(event => StateChannelSnapshotBinary(event.outputGist.lastSnapshotHash, event.outputBinary))
                  .reverse
            )
        }
        .toMap

      (result, Set.empty)
    }

  }
}
