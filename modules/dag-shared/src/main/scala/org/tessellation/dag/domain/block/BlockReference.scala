package org.tessellation.dag.domain.block

import cats.Order
import cats.effect.Async
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.height.Height
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.ProofsHash
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.ops._

@derive(encoder, decoder, eqv, show)
case class BlockReference(hash: ProofsHash, height: Height)

object BlockReference {
  implicit val blockReferenceOrder: Order[BlockReference] = (x: BlockReference, y: BlockReference) =>
    implicitly[Order[Long]].compare(x.height.coerce, y.height.coerce)

  def of[F[_]: Async: SecurityProvider: KryoSerializer](block: Signed[DAGBlock]): F[BlockReference] =
    block.hashWithSignatureCheck.flatMap(_.liftTo[F]).map { hashed =>
      BlockReference(hashed.proofsHash, hashed.height)
    }
}
