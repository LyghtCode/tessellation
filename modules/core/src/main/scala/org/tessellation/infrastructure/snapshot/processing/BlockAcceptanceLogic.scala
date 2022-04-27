package org.tessellation.infrastructure.snapshot.processing

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.either._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.semigroup._

import org.tessellation.dag.domain.block.{BlockReference, DAGBlock}
import org.tessellation.dag.snapshot.BlockAsActiveTip
import org.tessellation.infrastructure.snapshot.processing.NotAcceptedBlock.{awaitingBlock, invalidBlock}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.transaction.TransactionReference
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import monocle.syntax.all._

trait BlockAcceptanceLogic[F[_]] {

  def tryAcceptBlock(
    block: Signed[DAGBlock],
    state: BlockAcceptanceState
  ): BlockAcceptingM[F, BlockAsActiveTip]

}

object BlockAcceptanceLogic {

  def make[F[_]: Async: KryoSerializer: SecurityProvider]: BlockAcceptanceLogic[F] = new BlockAcceptanceLogic[F] {

    def tryAcceptBlock(
      block: Signed[DAGBlock],
      state: BlockAcceptanceState
    ): BlockAcceptingM[F, BlockAsActiveTip] =
      for {
        (updatedState1, usageCount) <- processParents(block, state)
        (updatedState2, _) <- processTransactions(block, updatedState1)

        tip = BlockAsActiveTip(block, usageCount)
      } yield (updatedState2, tip)

    private def processParents(
      signedBlock: Signed[DAGBlock],
      state: BlockAcceptanceState
    ): BlockAcceptingM[F, NonNegLong] =
      for {
        blockRef <- EitherT.liftF(BlockReference.of(signedBlock))

        (updatedState, usageCount) <- signedBlock.value.parent
          .foldLeft(rightT(state, initUsageCount)) { (acc, parent) =>
            acc.flatMap {
              case (state, usageCount) =>
                state.tipUsages
                  .get(parent)
                  .map { parentUsageCount =>
                    val updatedState = state
                      .focus(_.tipUsages)
                      .modify(_.updated(parent, parentUsageCount |+| usageIncrement))
                    val updatedUsageCount =
                      if (parentUsageCount >= deprecationThreshold)
                        usageCount |+| usageIncrement
                      else
                        usageCount
                    (updatedState, updatedUsageCount)
                  }
                  .toRight[NotAcceptedBlock](InvalidBlock(blockRef, ParentNotFound(parent)))
                  .toEitherT[F]
            }
          }
      } yield (updatedState, usageCount)

    private def processTransactions(
      signedBlock: Signed[DAGBlock],
      state: BlockAcceptanceState
    ): BlockAcceptingM[F, Unit] =
      for {
        blockRef <- EitherT.liftF(BlockReference.of(signedBlock))

        (updatedState, _) <- signedBlock.transactions.toList.sorted
          .foldLeft(rightT(state, none[BlockAwaitReason])) { (acc, tx) =>
            {
              EitherT.liftF(TransactionReference.of(tx)).flatMap { txRef =>
                acc.flatMap {
                  case (state, maybeNotReady) =>
                    val lastTxRef = state.lastTxRefs.getOrElse(tx.source, TransactionReference.empty)

                    if (tx.parent.ordinal < lastTxRef.ordinal) {
                      leftT(InvalidBlock(blockRef, InvalidTransaction(txRef, OrdinalTooLow(tx.ordinal))))
                    } else if (tx.parent.ordinal > lastTxRef.ordinal) {
                      // we don't want to "fail fast" yet, therefore `rightT` is used, even though we could use `leftT`
                      // this way we make sure that the block is validated before we classify it as awaiting
                      rightT(state, maybeNotReady.orElse(AwaitingTransaction(txRef, OrdinalTooHigh(tx.ordinal)).some))
                    } else {
                      if (tx.parent.hash =!= lastTxRef.hash) {
                        leftT(InvalidBlock(blockRef, InvalidTransaction(txRef, IncorrectParent(tx.parent))))
                      } else {
                        val balanceErrorOrState = for {
                          initSrcBalance <- state.balances.getOrElse(tx.source, Balance.empty).minus(tx.amount)
                          srcBalance <- initSrcBalance.minus(tx.fee)
                          dstBalance <- state.balances.getOrElse(tx.destination, Balance.empty).plus(tx.amount)

                          balances = state.balances.updated(tx.source, srcBalance).updated(tx.destination, dstBalance)
                          lastTxRefs = state.lastTxRefs.updated(tx.source, txRef)
                        } yield state.copy(balances = balances, lastTxRefs = lastTxRefs)

                        balanceErrorOrState
                          .map(state => (state, maybeNotReady))
                          .leftMap(err => invalidBlock(blockRef, InvalidTransaction(txRef, InvalidBalance(err))))
                          .toEitherT[F]
                      }
                    }
                }
              }
            }
          }
          .flatMap {
            case (state, maybeNotReady) =>
              maybeNotReady
                .map(reason => awaitingBlock(blockRef, reason))
                .toLeft((state, ()))
                .toEitherT[F]
          }

      } yield (updatedState, ())
  }
}
