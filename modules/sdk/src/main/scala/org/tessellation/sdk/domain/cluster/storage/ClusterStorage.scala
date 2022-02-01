package org.tessellation.sdk.domain.cluster.storage

import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.{Peer, PeerId}

import com.comcast.ip4s.{Host, Port}

trait ClusterStorage[F[_]] {
  def getPeers: F[Set[Peer]]
  def getPeers(host: Host): F[Set[Peer]]
  def getPeer(id: PeerId): F[Option[Peer]]
  def addPeer(peer: Peer): F[Unit]
  def hasPeerId(id: PeerId): F[Boolean]
  def hasPeerHostPort(host: Host, p2pPort: Port): F[Boolean]
  def setPeerState(id: PeerId, state: NodeState): F[Unit]
  def removePeer(id: PeerId): F[Unit]
}
