package org.tessellation.domain.aci

import org.tessellation.security.hash.Hash
import derevo.derive
import derevo.circe.magnolia.decoder

@derive(decoder)
case class StateChannelInput(lastSnapshotHash: Hash, bytes: Array[Byte])
