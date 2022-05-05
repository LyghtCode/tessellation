package org.tessellation.domain.cell

import org.tessellation.domain.aci.StateChannelOutput
import org.tessellation.security.signature.Signed
import org.tessellation.dag.domain.block.L1Output

sealed trait CoalgebraCommand

object CoalgebraCommand {
  case class ProcessDAGL1(data: Signed[L1Output]) extends CoalgebraCommand
  case class ProcessStateChannelSnapshot(snapshot: StateChannelOutput) extends CoalgebraCommand
}
