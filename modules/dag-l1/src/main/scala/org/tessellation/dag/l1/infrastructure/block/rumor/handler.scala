package org.tessellation.dag.l1.infrastructure.block.rumor

import cats.effect.Async
import cats.effect.std.Queue

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip.PeerRumor
import org.tessellation.sdk.infrastructure.gossip.{IgnoreSelfOrigin, RumorHandler}
import org.tessellation.security.signature.Signed

object handler {

  def blockRumorHandler[F[_]: Async: KryoSerializer](
    peerBlockQueue: Queue[F, Signed[DAGBlock]]
  ): RumorHandler[F] =
    RumorHandler.fromPeerRumorConsumer[F, Signed[DAGBlock]](IgnoreSelfOrigin) {
      case PeerRumor(_, _, signedBlock) =>
        peerBlockQueue.offer(signedBlock)
    }
}
