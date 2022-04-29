package org.tessellation.dag.l1.modules

import cats.effect.Concurrent
import cats.effect.std.Semaphore
import cats.syntax.flatMap._
import cats.syntax.functor._

object Semaphores {

  def make[F[_]: Concurrent]: F[Semaphores[F]] =
    for {
      blockAcceptance <- Semaphore(1)
      blockCreation <- Semaphore(1)
      blockStoring <- Semaphore(1)
    } yield
      new Semaphores[F](
        blockAcceptance = blockAcceptance,
        blockCreation = blockCreation,
        blockStoring = blockStoring
      ) {}
}

sealed abstract class Semaphores[F[_]] private (
  val blockAcceptance: Semaphore[F],
  val blockCreation: Semaphore[F],
  val blockStoring: Semaphore[F]
)
