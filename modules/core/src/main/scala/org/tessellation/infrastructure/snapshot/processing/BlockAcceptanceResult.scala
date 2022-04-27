package org.tessellation.infrastructure.snapshot.processing

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.dag.snapshot.BlockAsActiveTip
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
case class BlockAcceptanceResult(
  state: BlockAcceptanceState,
  acceptedBlocks: List[BlockAsActiveTip],
  awaitingBlocks: List[Signed[DAGBlock]]
)
