package org.tessellation.sdk.modules

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.syntax.functor._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.types.SdkConfig
import org.tessellation.sdk.domain.cluster.services.{Cluster, Session}
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.infrastructure.cluster.services.Cluster
import org.tessellation.sdk.infrastructure.gossip.Gossip
import org.tessellation.security.SecurityProvider

import fs2.concurrent.SignallingRef

object SdkServices {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    cfg: SdkConfig,
    nodeId: PeerId,
    keyPair: KeyPair,
    storages: SdkStorages[F],
    queues: SdkQueues[F],
    session: Session[F],
    whitelisting: Option[Set[PeerId]],
    restartSignal: SignallingRef[F, Unit]
  ): F[SdkServices[F]] = {
    val cluster = Cluster
      .make[F](
        cfg.leavingDelay,
        cfg.httpConfig,
        nodeId,
        keyPair,
        storages.cluster,
        storages.session,
        storages.node,
        whitelisting,
        restartSignal
      )

    for {
      gossip <- Gossip.make[F](queues.rumor, nodeId, keyPair)
    } yield
      new SdkServices[F](
        cluster = cluster,
        session = session,
        gossip = gossip
      ) {}
  }
}

sealed abstract class SdkServices[F[_]] private (
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F]
)
