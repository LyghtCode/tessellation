package org.tessellation.domain.aci

import org.tessellation.schema.address.Address

case class StateChannelGistedOutput(
  address: Address,
  outputBinary: Array[Byte]
)
