package org.tessellation.http.routes

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.ext.http4s.vars.AddressVar

import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.http4s.{EntityDecoder, HttpRoutes}
import cats.effect.std.Queue
import org.tessellation.domain.aci.StateChannelOutput
import org.tessellation.security.signature.Signed
import org.tessellation.domain.aci.StateChannelInput
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

final case class StateChannelRoutes[F[_]: Async](
  stateChannelOutoutQueue: Queue[F, StateChannelOutput]
) extends Http4sDsl[F] {
  private val prefixPath = "/state-channel"
  implicit val decoder: EntityDecoder[F, Array[Byte]] = EntityDecoder.byteArrayDecoder[F]

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / AddressVar(address) / "input" =>
      req
        .as[Signed[StateChannelInput]]
        .map(StateChannelOutput(address, _))
        .flatMap(stateChannelOutoutQueue.offer)
        .flatMap(_ => Ok())
  }

  val publicRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )
}
