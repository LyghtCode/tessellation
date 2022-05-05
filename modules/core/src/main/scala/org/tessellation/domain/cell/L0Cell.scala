package org.tessellation.domain.cell

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.functor._

import org.tessellation.domain.aci.StateChannelOutput
import org.tessellation.domain.cell.AlgebraCommand._
import org.tessellation.domain.cell.CoalgebraCommand._
import org.tessellation.domain.cell.L0Cell.{Algebra, Coalgebra}
import org.tessellation.kernel.Cell.NullTerminal
import org.tessellation.kernel.{Cell, CellError, _}

import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}
import org.tessellation.security.signature.Signed
import org.tessellation.dag.domain.block.L1Output

case class L0CellInput()

class L0Cell[F[_]: Async](
  data: L0CellInput
) extends Cell[F, StackF, L0CellInput, Either[CellError, Ω], CoalgebraCommand](
      data, {
        scheme.hyloM(
          AlgebraM[F, StackF, Either[CellError, Ω]] {
            case More(a) => a.pure[F]
            case Done(Right(cmd: AlgebraCommand)) =>
              cmd match {
                case EnqueueStateChannelSnapshot(snapshot) =>
                  Algebra.enqueueStateChannelSnapshot(snapshot)
                case EnqueueDAGL1Data(data) =>
                  Algebra.enqueueDAGL1Data(data)
                case NoAction =>
                  NullTerminal.asRight[CellError].widen[Ω].pure[F]
              }
            case Done(other) => other.pure[F]
          },
          CoalgebraM[F, StackF, CoalgebraCommand] {
            case ProcessDAGL1(data)                    => Coalgebra.processDAGL1(data)
            case ProcessStateChannelSnapshot(snapshot) => Coalgebra.processStateChannelSnapshot(snapshot)
          }
        )
      }, {
        case _ => ???
      }
    )

object L0Cell {

  type AlgebraR[F[_]] = F[Either[CellError, Ω]]
  type CoalgebraR[F[_]] = F[StackF[CoalgebraCommand]]

  object Algebra {
    def enqueueStateChannelSnapshot[F[_]: Async](snapshot: StateChannelOutput): AlgebraR[F] = ???
    def enqueueDAGL1Data[F[_]: Async](data: Signed[L1Output]): AlgebraR[F] = ???
  }

  object Coalgebra {

    def processDAGL1[F[_]: Async](data: Signed[L1Output]): CoalgebraR[F] = {
      def res: StackF[CoalgebraCommand] = Done(AlgebraCommand.EnqueueDAGL1Data(data).asRight[CellError])

      res.pure[F]
    }

    def processStateChannelSnapshot[F[_]: Async](snapshot: StateChannelOutput): CoalgebraR[F] = {
      def res: StackF[CoalgebraCommand] = Done(AlgebraCommand.EnqueueStateChannelSnapshot(snapshot).asRight[CellError])

      res.pure[F]
    }
  }
}
