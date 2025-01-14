package org.tessellation.security

import cats.Order

import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
case class Hashed[A <: AnyRef](signed: Signed[A], hash: Hash, proofsHash: ProofsHash)

object Hashed {
  implicit def autoUnwrap[T <: AnyRef](t: Hashed[T]): T = t.signed

  implicit def order[A <: AnyRef: Order]: Order[Hashed[A]] = (x: Hashed[A], y: Hashed[A]) => Order[A].compare(x, y)

  implicit def ordering[A <: AnyRef: Order]: Ordering[Hashed[A]] = order.toOrdering
}
