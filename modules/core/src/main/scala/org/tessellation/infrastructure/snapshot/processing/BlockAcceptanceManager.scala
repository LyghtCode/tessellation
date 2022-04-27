package org.tessellation.infrastructure.snapshot.processing

import cats.Applicative
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.show._

import org.tessellation.dag.domain.block.{BlockReference, DAGBlock}
import org.tessellation.dag.snapshot.BlockAsActiveTip
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import monocle.syntax.all._
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait BlockAcceptanceManager[F[_]] {

  def processBlocks(
    initState: BlockAcceptanceState,
    blocks: List[Signed[DAGBlock]]
  ): F[BlockAcceptanceResult]

}

object BlockAcceptanceManager {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](logic: BlockAcceptanceLogic[F]): BlockAcceptanceManager[F] =
    new BlockAcceptanceManager[F] {
      private val logger = Slf4jLogger.getLoggerFromClass[F](BlockAcceptanceManager.getClass)

      def processBlocks(
        initState: BlockAcceptanceState,
        blocks: List[Signed[DAGBlock]]
      ): F[BlockAcceptanceResult] = {

        def go(prevResult: BlockAcceptanceResult): F[BlockAcceptanceResult] = {
          val initResult = prevResult.focus(_.awaitingBlocks).replace(List.empty)
          for {
            currResult <- prevResult.awaitingBlocks.foldLeftM(initResult) { (acc, block) =>
              logic
                .tryAcceptBlock(block, acc.state)
                .semiflatTap(tuple => logAcceptedBlock(tuple._2))
                .leftSemiflatTap(logNotAcceptedBlock)
                .map {
                  case (state, blockAsActiveTip) =>
                    acc
                      .focus(_.state)
                      .replace(state)
                      .focus(_.acceptedBlocks)
                      .modify(blockAsActiveTip :: _)
                }
                .leftMap {
                  case InvalidBlock(_, _) => acc
                  case AwaitingBlock(_, _) =>
                    acc
                      .focus(_.awaitingBlocks)
                      .modify(_ :+ block)
                }
                .merge
            }
            finalResult <- if (prevResult.state === currResult.state) currResult.pure[F]
            else go(currResult)
          } yield finalResult
        }

        go(BlockAcceptanceResult(initState, List.empty, blocks))

      }

      private def logAcceptedBlock(signedBlock: BlockAsActiveTip): F[Unit] =
        logger.isTraceEnabled.ifM(
          BlockReference.of(signedBlock.block).flatMap { blockRef =>
            logger.trace(s"Accepted block: ${blockRef.show}")
          },
          Applicative[F].unit
        )

      private def logNotAcceptedBlock(notAcceptedBlock: NotAcceptedBlock): F[Unit] =
        notAcceptedBlock match {
          case InvalidBlock(blockRef, reason) =>
            logger.info(s"Invalid block: ${blockRef.show}, reason: ${reason.show}")
          case AwaitingBlock(blockRef, reason) =>
            logger.trace(s"Awaiting block: ${blockRef.show}, reason: ${reason.show}")
        }
    }

}
