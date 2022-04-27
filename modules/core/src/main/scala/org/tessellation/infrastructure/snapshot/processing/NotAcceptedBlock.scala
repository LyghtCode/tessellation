package org.tessellation.infrastructure.snapshot.processing

import org.tessellation.dag.domain.block.BlockReference
import org.tessellation.schema.balance.BalanceOutOfRange
import org.tessellation.schema.transaction.{TransactionOrdinal, TransactionReference}

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
sealed trait NotAcceptedBlock
case class InvalidBlock(blockRef: BlockReference, reason: BlockValidationError) extends NotAcceptedBlock
case class AwaitingBlock(blockRef: BlockReference, reason: BlockAwaitReason) extends NotAcceptedBlock

object NotAcceptedBlock {

  def invalidBlock(blockRef: BlockReference, reason: BlockValidationError): NotAcceptedBlock =
    InvalidBlock(blockRef, reason)

  def awaitingBlock(blockRef: BlockReference, reason: BlockAwaitReason): NotAcceptedBlock =
    AwaitingBlock(blockRef, reason)

}

@derive(eqv, show)
sealed trait BlockValidationError
case class ParentNotFound(parent: BlockReference) extends BlockValidationError
case class InvalidTransaction(tx: TransactionReference, txError: TransactionValidationError)
    extends BlockValidationError

@derive(eqv, show)
sealed trait TransactionValidationError
case class IncorrectParent(parent: TransactionReference) extends TransactionValidationError
case class OrdinalTooLow(ordinal: TransactionOrdinal) extends TransactionValidationError
case class InvalidBalance(boor: BalanceOutOfRange) extends TransactionValidationError

@derive(eqv, show)
sealed trait BlockAwaitReason
case class AwaitingTransaction(tx: TransactionReference, reason: TransactionAwaitReason) extends BlockAwaitReason

@derive(eqv, show)
sealed trait TransactionAwaitReason
case class OrdinalTooHigh(ordinal: TransactionOrdinal) extends TransactionAwaitReason
