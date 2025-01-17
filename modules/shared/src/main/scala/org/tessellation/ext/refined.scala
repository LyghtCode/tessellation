package org.tessellation.ext

import _root_.cats.syntax.order._
import _root_.cats.{Eq, Order, Show}
import eu.timepit.refined.api.{Refined, Validate}
import eu.timepit.refined.refineV
import io.circe.{Decoder, Encoder}

object refined {

  // For exemplary validator definition look into DAGAddressRefined object

  def decoderOf[T, P](implicit v: Validate[T, P], d: Decoder[T]): Decoder[T Refined P] =
    d.emap(refineV[P].apply[T](_))

  def encoderOf[T, P](implicit e: Encoder[T]): Encoder[T Refined P] =
    e.contramap(_.value)

  def eqOf[T, P](implicit eqT: Eq[T]): Eq[T Refined P] =
    Eq.instance((a, b) => eqT.eqv(a.value, b.value))

  def showOf[T, P](implicit showT: Show[T]): Show[T Refined P] =
    Show.show(r => showT.show(r.value))

  def orderOf[T, P](implicit orderT: Order[T]): Order[T Refined P] =
    (x: T Refined P, y: T Refined P) => x.value.compare(y.value)

}
