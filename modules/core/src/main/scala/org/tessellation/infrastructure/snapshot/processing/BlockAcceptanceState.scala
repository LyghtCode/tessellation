package org.tessellation.infrastructure.snapshot.processing

import org.tessellation.dag.domain.block.BlockReference
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.transaction.TransactionReference

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

@derive(eqv, show)
case class BlockAcceptanceState(
  lastTxRefs: Map[Address, TransactionReference],
  balances: Map[Address, Balance],
  tipUsages: Map[BlockReference, NonNegLong]
)
