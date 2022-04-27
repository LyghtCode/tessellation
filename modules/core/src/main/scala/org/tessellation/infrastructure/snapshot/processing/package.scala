package org.tessellation.infrastructure.snapshot

import cats.Applicative
import cats.data.EitherT
import cats.syntax.either._

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

package object processing {

  // TODO replace NonNegLong with @newtype UsageCount and create `Next[UsageCount]` instance for it
  val usageIncrement: NonNegLong = 1L
  val initUsageCount: NonNegLong = 0L
  val deprecationThreshold: NonNegLong = 2L

  type BlockAcceptingM[F[_], A] =
    EitherT[F, NotAcceptedBlock, (BlockAcceptanceState, A)]

  def rightT[F[_]: Applicative, A](s: BlockAcceptanceState, a: A): BlockAcceptingM[F, A] =
    (s, a)
      .asRight[NotAcceptedBlock]
      .toEitherT[F]

  def leftT[F[_]: Applicative, A](nr: NotAcceptedBlock): BlockAcceptingM[F, A] =
    nr.asLeft[(BlockAcceptanceState, A)].toEitherT[F]

}
