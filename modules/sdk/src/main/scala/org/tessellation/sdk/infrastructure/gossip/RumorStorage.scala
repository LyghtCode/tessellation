package org.tessellation.sdk.infrastructure.gossip

import cats.effect.{Async, Spawn, Temporal}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.traverse._
import cats.syntax.traverseFilter._

import org.tessellation.schema.gossip._
import org.tessellation.sdk.config.types.RumorStorageConfig
import org.tessellation.sdk.domain.gossip.RumorStorage
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto.autoUnwrap
import io.chrisdavenport.mapref.MapRef

object RumorStorage {

  def make[F[_]: Async](cfg: RumorStorageConfig): F[RumorStorage[F]] =
    for {
      active <- MapRef.ofConcurrentHashMap[F, Hash, Signed[RumorBinary]]()
      seen <- MapRef.ofConcurrentHashMap[F, Hash, Unit]()
    } yield make(active, seen, cfg)

  def make[F[_]: Async](
    active: MapRef[F, Hash, Option[Signed[RumorBinary]]],
    seen: MapRef[F, Hash, Option[Unit]],
    cfg: RumorStorageConfig
  ): RumorStorage[F] =
    new RumorStorage[F] {

      def addRumors(rumors: RumorBatch): F[RumorBatch] =
        for {
          unseen <- setRumorsSeenAndGetUnseen(rumors)
          _ <- setRumorsActive(unseen)
        } yield unseen

      def getRumors(hashes: List[Hash]): F[RumorBatch] =
        hashes.flatTraverse { hash =>
          active(hash).get.map(opt => opt.map(r => List(hash -> r)).getOrElse(List.empty[HashAndRumor]))
        }

      def getActiveHashes: F[List[Hash]] = active.keys

      def getSeenHashes: F[List[Hash]] = seen.keys

      private def setRumorsSeenAndGetUnseen(rumors: RumorBatch): F[RumorBatch] =
        for {
          unseen <- rumors.traverseFilter {
            case p @ (hash, _) =>
              seen(hash).getAndSet(().some).map {
                case Some(_) => none[HashAndRumor]
                case None    => p.some
              }
          }
        } yield unseen

      private def setRumorsActive(rumors: RumorBatch): F[Unit] =
        for {
          _ <- rumors.traverse { case (hash, rumor) => active(hash).set(rumor.some) }
          _ <- Spawn[F].start(setRetention(rumors))
        } yield ()

      private def setRetention(rumors: RumorBatch): F[Unit] =
        for {
          _ <- Temporal[F].sleep(cfg.activeRetention)
          _ <- rumors.traverse { case (hash, _) => active(hash).set(none[Signed[PeerRumorBinary]]) }
          _ <- Temporal[F].sleep(cfg.seenRetention)
          _ <- rumors.traverse { case (hash, _) => seen(hash).set(none[Unit]) }
        } yield ()
    }

}
