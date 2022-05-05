package org.tessellation.domain.aci

import org.tessellation.schema.address.Address
import org.tessellation.security.signature.Signed

case class StateChannelOutput (
  address: Address,
  input: Signed[StateChannelInput]
)
