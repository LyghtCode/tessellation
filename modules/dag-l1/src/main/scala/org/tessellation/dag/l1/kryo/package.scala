package org.tessellation.dag.l1

import java.util.UUID

import org.tessellation.dag.domain.block.{BlockReference, DAGBlock}
import org.tessellation.dag.l1.domain.block.Tips
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput._
import org.tessellation.dag.l1.domain.consensus.block.CancellationReason
import org.tessellation.dag.l1.domain.consensus.block.CancellationReason._

package object kryo {

  val stateChannelKryoRegistrar: Map[Class[_], Int] = Map(
    classOf[PeerBlockConsensusInput] -> 1001,
    classOf[Proposal] -> 1002,
    classOf[BlockProposal] -> 1003,
    classOf[CancelledBlockCreationRound] -> 1004,
    classOf[UUID] -> 1005,
    classOf[Tips] -> 1006,
    classOf[BlockReference] -> 1007,
    classOf[DAGBlock] -> 1008,
    classOf[CancellationReason] -> 1009,
    ReceivedProposalForNonExistentOwnRound.getClass -> 1010,
    MissingRoundPeers.getClass -> 1011,
    CreatedInvalidBlock.getClass -> 1012,
    PeerCancelled.getClass -> 1013
  )
}