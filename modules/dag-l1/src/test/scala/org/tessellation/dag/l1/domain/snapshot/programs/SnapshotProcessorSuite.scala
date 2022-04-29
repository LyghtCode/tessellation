package org.tessellation.dag.l1.domain.snapshot.programs

import java.security.KeyPair

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.std.Random
import cats.effect.{IO, Ref, Resource}
import cats.syntax.either._
import cats.syntax.option._
import cats.syntax.traverse._

import org.tessellation.dag.domain.block.{BlockReference, DAGBlock}
import org.tessellation.dag.l1.Main
import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.block.BlockStorage._
import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor._
import org.tessellation.dag.l1.domain.snapshot.storage.LastGlobalSnapshotStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.snapshot._
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.collection.MapRefUtils._
import org.tessellation.keytool.KeyPairGenerator
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.{Transaction, TransactionOrdinal, TransactionReference}
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed.forAsyncKryo
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import io.chrisdavenport.mapref.MapRef
import weaver.SimpleIOSuite

object SnapshotProcessorSuite extends SimpleIOSuite {

  type TestResources = (
    SnapshotProcessor[IO],
    SecurityProvider[IO],
    KryoSerializer[IO],
    KeyPair,
    Address,
    PeerId,
    Ref[IO, Map[Address, Balance]],
    MapRef[IO, ProofsHash, Option[StoredBlock]],
    Ref[IO, Option[Hashed[GlobalSnapshot]]],
    MapRef[IO, Address, Option[TransactionReference]]
  )

  def testResources: Resource[IO, TestResources] =
    SecurityProvider.forAsync[IO].flatMap { implicit sp =>
      KryoSerializer.forAsync[IO](Main.kryoRegistrar ++ sdkKryoRegistrar).flatMap { implicit kp =>
        Random.scalaUtilRandom[IO].asResource.flatMap { implicit random =>
          for {
            balancesR <- Ref.of[IO, Map[Address, Balance]](Map.empty).asResource
            blocksR <- MapRef.ofConcurrentHashMap[IO, ProofsHash, StoredBlock]().asResource
            lastSnapR <- Ref.of[IO, Option[Hashed[GlobalSnapshot]]](None).asResource
            lastAccTxR <- MapRef.ofConcurrentHashMap[IO, Address, TransactionReference]().asResource
            waitingTxsR <- MapRef.ofConcurrentHashMap[IO, Address, NonEmptySet[Signed[Transaction]]]().asResource
            snapshotProcessor = {
              val addressStorage = new AddressStorage[IO] {
                def getBalance(address: Address): IO[balance.Balance] =
                  balancesR.get.map(b => b(address))

                def updateBalances(addressBalances: Map[Address, balance.Balance]): IO[Unit] =
                  balancesR.set(addressBalances)

                def clean: IO[Unit] = balancesR.set(Map.empty)
              }

              val blockStorage = new BlockStorage[IO](blocksR)
              val lastGlobalSnapshotStorage = LastGlobalSnapshotStorage.make(lastSnapR)
              val transactionStorage = new TransactionStorage[IO](lastAccTxR, waitingTxsR)

              SnapshotProcessor
                .make[IO](addressStorage, blockStorage, lastGlobalSnapshotStorage, transactionStorage)
            }
            key <- KeyPairGenerator.makeKeyPair[IO].asResource
            address = key.getPublic.toAddress
            peerId = PeerId.fromId(key.getPublic.toId)
          } yield (snapshotProcessor, sp, kp, key, address, peerId, balancesR, blocksR, lastSnapR, lastAccTxR)
        }
      }
    }

  val lastSnapshotOrdinal = SnapshotOrdinal(10L)
  val nextSnapshotOrdinal = SnapshotOrdinal(11L)
  val lastSnapshotHeight = Height(6L)
  val nextSnapshotHeight = Height(8L)

  def generateSnapshotBalances(address: Address) = Map(address -> Balance(50L))

  def generateSnapshotLastAccTxRefs(address: Address) =
    Map(address -> TransactionReference(Hash("lastTx"), TransactionOrdinal(2L)))

  def generateSnapshot(peerId: PeerId): GlobalSnapshot =
    GlobalSnapshot(
      lastSnapshotOrdinal,
      lastSnapshotHeight,
      SubHeight(0L),
      Hash("hash"),
      Set.empty,
      Map.empty,
      Set.empty,
      NonEmptyList.one(peerId),
      GlobalSnapshotInfo(Map.empty, Map.empty, Map.empty),
      GlobalSnapshotTips(Set.empty, Set.empty)
    )

  test("download should happen for the base no blocks case") {
    testResources.use {
      case (snapshotProcessor, sp, kp, key, address, peerId, balancesR, blocksR, lastSnapR, lastAccTxR) =>
        implicit val securityProvider = sp
        implicit val kryoPool = kp

        val block = DAGBlock(Set.empty, NonEmptyList.one(BlockReference(ProofsHash("parent1"), Height(4L))))

        for {
          hashedBlock <- forAsyncKryo(block, key).flatMap(_.hashWithSignatureCheck.map(_.toOption.get))
          snapshotBalances = generateSnapshotBalances(address)
          snapshotTxRefs = generateSnapshotLastAccTxRefs(address)
          hashedSnapshot <- forAsyncKryo(
            generateSnapshot(peerId)
              .copy(
                blocks = Set(BlockAsActiveTip(hashedBlock.signed, NonNegLong.MinValue)),
                info = GlobalSnapshotInfo(Map.empty, snapshotTxRefs, snapshotBalances)
              ),
            key
          ).flatMap(_.hashWithSignatureCheck.map(_.toOption.get))
          balancesBefore <- balancesR.get
          blocksBefore <- blocksR.toMap
          lastGlobalSnapshotBefore <- lastSnapR.get
          lastAcceptedTxRBefore <- lastAccTxR.toMap

          processingResult <- snapshotProcessor.process(hashedSnapshot)

          balancesAfter <- balancesR.get
          blocksAfter <- blocksR.toMap
          lastGlobalSnapshotAfter <- lastSnapR.get
          lastAcceptedTxRAfter <- lastAccTxR.toMap
        } yield
          expect
            .same(
              (
                processingResult,
                balancesBefore,
                balancesAfter,
                blocksBefore,
                blocksAfter,
                lastGlobalSnapshotBefore,
                lastGlobalSnapshotAfter,
                lastAcceptedTxRBefore,
                lastAcceptedTxRAfter
              ),
              (
                DownloadPerformed(
                  lastSnapshotHeight,
                  lastSnapshotOrdinal,
                  hashedSnapshot.hash,
                  hashedSnapshot.proofsHash,
                  Set(hashedBlock.proofsHash),
                  Set.empty
                ),
                Map.empty,
                snapshotBalances,
                Map.empty,
                Map(
                  hashedBlock.proofsHash -> MajorityBlock(
                    BlockReference(hashedBlock.proofsHash, hashedBlock.height),
                    NonNegLong.MinValue,
                    Active
                  ) /*,
                  ProofsHash("parent1") -> BlockStorage.UnknownBlock(Set(hashedBlock.proofsHash))*/
                ),
                None,
                Some(hashedSnapshot),
                Map.empty,
                snapshotTxRefs
              )
            )
    }
  }

  test("download should happen for the case when there are waiting blocks in the storage") {
    testResources.use {
      case (snapshotProcessor, sp, kp, key, _, peerId, _, blocksR, _, _) =>
        implicit val securityProvider = sp
        implicit val kryoPool = kp

        val parent1 = BlockReference(ProofsHash("parent1"), Height(8L))
        val parent2 = BlockReference(ProofsHash("parent2"), Height(2L))
        val parent3 = BlockReference(ProofsHash("parent3"), Height(5L))
        val aboveRangeBlock = DAGBlock(Set.empty, NonEmptyList.one(parent1))
        val nonMajorityInRangeBlock = DAGBlock(Set.empty, NonEmptyList.one(parent2))
        val majorityInRangeBlock = DAGBlock(Set.empty, NonEmptyList.one(parent3))

        val blocks = List(aboveRangeBlock, nonMajorityInRangeBlock, majorityInRangeBlock)

        for {
          hashedBlocks <- blocks.traverse(
            forAsyncKryo(_, key).flatMap(_.hashWithSignatureCheck.map(_.toOption.get))
          )
          hashedSnapshot <- forAsyncKryo(
            generateSnapshot(peerId).copy(blocks = Set(BlockAsActiveTip(hashedBlocks(2).signed, NonNegLong(1L)))),
            key
          ).flatMap(_.hashWithSignatureCheck.map(_.toOption.get))

//          // Inserting parents
//          _ <- blocksR(parent1.hash).set(UnknownBlock(Set(hashedBlocks.head.proofsHash)).some)
//          _ <- blocksR(parent2.hash).set(UnknownBlock(Set(hashedBlocks(1).proofsHash)).some)
//          _ <- blocksR(parent3.hash).set(UnknownBlock(Set(hashedBlocks(2).proofsHash)).some)
          // Inserting blocks in required state
          _ <- blocksR(hashedBlocks.head.proofsHash).set(WaitingBlock(hashedBlocks.head.signed).some)
          _ <- blocksR(hashedBlocks(1).proofsHash).set(WaitingBlock(hashedBlocks(1).signed).some)
          _ <- blocksR(hashedBlocks(2).proofsHash).set(WaitingBlock(hashedBlocks(2).signed).some)

          processingResult <- snapshotProcessor.process(hashedSnapshot)

          blocksAfter <- blocksR.toMap
        } yield
          expect.same(
            (processingResult, blocksAfter),
            (
              DownloadPerformed(
                lastSnapshotHeight,
                lastSnapshotOrdinal,
                hashedSnapshot.hash,
                hashedSnapshot.proofsHash,
                Set(hashedBlocks(2).proofsHash),
                Set(hashedBlocks(1).proofsHash)
              ),
              Map(
//                parent1.hash -> UnknownBlock(Set(hashedBlocks.head.proofsHash)),
//                parent3.hash -> UnknownBlock(Set(hashedBlocks(2).proofsHash)),
                hashedBlocks.head.proofsHash -> WaitingBlock(hashedBlocks.head.signed),
                hashedBlocks(2).proofsHash -> MajorityBlock(
                  BlockReference(hashedBlocks(2).proofsHash, hashedBlocks(2).height),
                  NonNegLong(1L),
                  Active
                )
              )
            )
          )
    }
  }

  test("alignment at same height should happen when snapshot with new ordinal but known height is processed") {
    testResources.use {
      case (snapshotProcessor, sp, kp, key, _, peerId, _, _, lastSnapR, _) =>
        implicit val securityProvider = sp
        implicit val kryoPool = kp

        for {
          hashedLastSnapshot <- forAsyncKryo(
            generateSnapshot(peerId),
            key
          ).flatMap(_.hashWithSignatureCheck.map(_.toOption.get))
          hashedNextSnapshot <- forAsyncKryo(
            generateSnapshot(peerId).copy(
              ordinal = nextSnapshotOrdinal,
              subHeight = SubHeight(1L),
              lastSnapshotHash = hashedLastSnapshot.hash
            ),
            key
          ).flatMap(_.hashWithSignatureCheck.map(_.toOption.get))
          _ <- lastSnapR.set(hashedLastSnapshot.some)

          processingResult <- snapshotProcessor.process(hashedNextSnapshot)

          lastSnapshotAfter <- lastSnapR.get.map(_.get)
        } yield
          expect.same(
            (processingResult, lastSnapshotAfter),
            (
              Aligned(
                lastSnapshotHeight,
                nextSnapshotOrdinal,
                hashedNextSnapshot.hash,
                hashedNextSnapshot.proofsHash,
                Set.empty
              ),
              hashedNextSnapshot
            )
          )
    }
  }

  test("alignment at new height should happen when node is aligned with the majority in processed snapshot") {
    testResources.use {
      case (snapshotProcessor, sp, kp, key, _, peerId, _, blocksR, lastSnapR, _) =>
        implicit val securityProvider = sp
        implicit val kryoPool = kp

        val parent1 = BlockReference(ProofsHash("parent1"), Height(10L))
        val parent2 = BlockReference(ProofsHash("parent2"), Height(2L))
        val parent3 = BlockReference(ProofsHash("parent3"), Height(7L))
        val aboveRangeBlock = DAGBlock(Set.empty, NonEmptyList.one(parent1))
        val majorityBelowRangeBlock = DAGBlock(Set.empty, NonEmptyList.one(parent2))
        val nonMajorityBelowRangeBlock = DAGBlock(Set.empty, NonEmptyList.one(parent2))
        val majorityInRangeBlock = DAGBlock(Set.empty, NonEmptyList.one(parent3))

        val blocks =
          List(aboveRangeBlock, majorityBelowRangeBlock, nonMajorityBelowRangeBlock, majorityInRangeBlock)

        for {
          hashedBlocks <- blocks.traverse(
            forAsyncKryo(_, key).flatMap(_.hashWithSignatureCheck.map(_.toOption.get))
          )
          hashedLastSnapshot <- forAsyncKryo(
            generateSnapshot(peerId),
            key
          ).flatMap(_.hashWithSignatureCheck.map(_.toOption.get))
          hashedNextSnapshot <- forAsyncKryo(
            generateSnapshot(peerId).copy(
              ordinal = nextSnapshotOrdinal,
              height = nextSnapshotHeight,
              lastSnapshotHash = hashedLastSnapshot.hash,
              blocks = Set(hashedBlocks(3).signed)
            ),
            key
          ).flatMap(_.hashWithSignatureCheck.map(_.toOption.get))
          _ <- lastSnapR.set(hashedLastSnapshot.some)
          // Inserting parents
          _ <- blocksR(parent1.hash).set(UnknownBlock(Set(hashedBlocks.head.proofsHash)).some)
          _ <- blocksR(parent2.hash).set(UnknownBlock(Set(hashedBlocks(1).proofsHash, hashedBlocks(2).proofsHash)).some)
          _ <- blocksR(parent3.hash).set(UnknownBlock(Set(hashedBlocks(3).proofsHash)).some)
          // Inserting blocks in required state
          _ <- blocksR(hashedBlocks.head.proofsHash)
            .set(AcceptedBlock(hashedBlocks.head, Accepted, Set.empty, false).some)
          _ <- blocksR(hashedBlocks(1).proofsHash)
            .set(AcceptedBlock(hashedBlocks(1), InMajority, Set.empty, false).some)
          _ <- blocksR(hashedBlocks(2).proofsHash)
            .set(WaitingBlock(hashedBlocks(2).signed, Set.empty).some)
          _ <- blocksR(hashedBlocks(3).proofsHash)
            .set(AcceptedBlock(hashedBlocks(3), Accepted, Set.empty, false).some)

          processingResult <- snapshotProcessor.process(hashedNextSnapshot)

          blocksAfter <- blocksR.toMap
        } yield
          expect.same(
            (processingResult, blocksAfter),
            (
              Aligned(
                nextSnapshotHeight,
                nextSnapshotOrdinal,
                hashedNextSnapshot.hash,
                hashedNextSnapshot.proofsHash,
                Set(hashedBlocks(2).proofsHash)
              ),
              Map(
                parent1.hash -> UnknownBlock(Set(hashedBlocks.head.proofsHash)),
                parent2.hash -> UnknownBlock(Set(hashedBlocks(1).proofsHash)),
                parent3.hash -> UnknownBlock(Set(hashedBlocks(3).proofsHash)),
                hashedBlocks.head.proofsHash -> AcceptedBlock(
                  hashedBlocks.head,
                  Accepted,
                  Set.empty,
                  false
                ),
                hashedBlocks(1).proofsHash -> AcceptedBlock(
                  hashedBlocks(1),
                  InMajority,
                  Set.empty,
                  false
                ),
                hashedBlocks(3).proofsHash -> AcceptedBlock(
                  hashedBlocks(3),
                  InMajority,
                  Set.empty,
                  false
                )
              )
            )
          )
    }
  }
//
//  test("redownload should happen when node is misaligned with majority in processed snapshot") {
//    testResources.use {
//      case (snapshotProcessor, sp, kp, key, address, peerId, balancesR, blocksR, lastSnapR, lastAccTxR) =>
//        implicit val securityProvider = sp
//        implicit val kryoPool = kp
//
//        val parent1 = BlockReference(ProofsHash("parent1"), Height(10L))
//        val parent2 = BlockReference(ProofsHash("parent2"), Height(2L))
//        val parent3 = BlockReference(ProofsHash("parent3"), Height(7L))
//        val parent4 = BlockReference(ProofsHash("parent4"), Height(6L))
//        val parent5 = BlockReference(ProofsHash("parent5"), Height(6L))
//
//        val aboveRangeBlock = DAGBlock(Set.empty, NonEmptyList.one(parent1))
//        val majorityBelowRangeBlock = DAGBlock(Set.empty, NonEmptyList.one(parent2))
//        val nonMajorityBelowRangeBlock = DAGBlock(Set.empty, NonEmptyList.one(parent2))
//        val nonMajorityInRangeBlock = DAGBlock(Set.empty, NonEmptyList.one(parent3))
//        val majorityInRangeBlock = DAGBlock(Set.empty, NonEmptyList.one(parent3))
//        val majorityUnknownInRangeBlock = DAGBlock(Set.empty, NonEmptyList.one(parent4))
//        val majorityWaitingInRangeBlock = DAGBlock(Set.empty, NonEmptyList.one(parent5))
//        val nonMajorityWaitingInRangeBlock = DAGBlock(Set.empty, NonEmptyList.one(parent5))
//
//        val blocks =
//          List(
//            aboveRangeBlock,
//            majorityBelowRangeBlock,
//            nonMajorityBelowRangeBlock,
//            nonMajorityInRangeBlock,
//            majorityInRangeBlock,
//            majorityUnknownInRangeBlock,
//            majorityWaitingInRangeBlock,
//            nonMajorityWaitingInRangeBlock
//          )
//
//        for {
//          hashedBlocks <- blocks.traverse(
//            forAsyncKryo(_, key).flatMap(_.hashWithSignatureCheck.map(_.toOption.get))
//          )
//          hashedLastSnapshot <- forAsyncKryo(
//            generateSnapshot(peerId),
//            key
//          ).flatMap(_.hashWithSignatureCheck.map(_.toOption.get))
//
//          snapshotBalances = generateSnapshotBalances(address)
//          snapshotTxRefs = generateSnapshotLastAccTxRefs(address)
//          hashedNextSnapshot <- forAsyncKryo(
//            generateSnapshot(peerId).copy(
//              ordinal = nextSnapshotOrdinal,
//              height = nextSnapshotHeight,
//              lastSnapshotHash = hashedLastSnapshot.hash,
//              blocks = Set(hashedBlocks(4).signed, hashedBlocks(5).signed, hashedBlocks(6).signed),
//              info = GlobalSnapshotInfo(Map.empty, snapshotTxRefs, snapshotBalances)
//            ),
//            key
//          ).flatMap(_.hashWithSignatureCheck.map(_.toOption.get))
//          _ <- lastSnapR.set(hashedLastSnapshot.some)
//          // Inserting parents
//          _ <- blocksR(parent1.hash).set(UnknownBlock(Set(hashedBlocks.head.proofsHash)).some)
//          _ <- blocksR(parent2.hash).set(UnknownBlock(Set(hashedBlocks(1).proofsHash, hashedBlocks(2).proofsHash)).some)
//          _ <- blocksR(parent3.hash).set(UnknownBlock(Set(hashedBlocks(3).proofsHash, hashedBlocks(4).proofsHash)).some)
//          _ <- blocksR(parent5.hash).set(UnknownBlock(Set(hashedBlocks(6).proofsHash, hashedBlocks(7).proofsHash)).some)
//          // Inserting blocks in required state
//          _ <- blocksR(hashedBlocks.head.proofsHash)
//            .set(AcceptedBlock(hashedBlocks.head, Accepted, Set.empty, false).some)
//          _ <- blocksR(hashedBlocks(1).proofsHash)
//            .set(AcceptedBlock(hashedBlocks(1), InMajority, Set.empty, false).some)
//          _ <- blocksR(hashedBlocks(2).proofsHash)
//            .set(WaitingBlock(hashedBlocks(2).signed, Set.empty).some)
//          _ <- blocksR(hashedBlocks(3).proofsHash)
//            .set(AcceptedBlock(hashedBlocks(3), Accepted, Set.empty, false).some)
//          _ <- blocksR(hashedBlocks(4).proofsHash)
//            .set(AcceptedBlock(hashedBlocks(4), Accepted, Set.empty, false).some)
//          _ <- blocksR(hashedBlocks(6).proofsHash)
//            .set(WaitingBlock(hashedBlocks(6).signed, Set.empty).some)
//          _ <- blocksR(hashedBlocks(7).proofsHash)
//            .set(WaitingBlock(hashedBlocks(7).signed, Set.empty).some)
//
//          processingResult <- snapshotProcessor.process(hashedNextSnapshot)
//
//          blocksAfter <- blocksR.toMap
//          balancesAfter <- balancesR.get
//          lastSnapshotAfter <- lastSnapR.get.map(_.get)
//          lastTxRefsAfter <- lastAccTxR.toMap
//        } yield
//          expect.same(
//            (processingResult, blocksAfter, balancesAfter, lastSnapshotAfter, lastTxRefsAfter),
//            (
//              RedownloadPerformed(
//                nextSnapshotHeight,
//                nextSnapshotOrdinal,
//                hashedNextSnapshot.hash,
//                hashedNextSnapshot.proofsHash,
//                Set(hashedBlocks(5).proofsHash, hashedBlocks(6).proofsHash),
//                Set(hashedBlocks(3).proofsHash),
//                Set(hashedBlocks(2).proofsHash, hashedBlocks(7).proofsHash)
//              ),
//              Map(
//                parent1.hash -> UnknownBlock(Set(hashedBlocks.head.proofsHash)),
//                parent2.hash -> UnknownBlock(Set(hashedBlocks(1).proofsHash)),
//                parent3.hash -> UnknownBlock(Set(hashedBlocks(4).proofsHash)),
//                parent4.hash -> UnknownBlock(Set(hashedBlocks(5).proofsHash)),
//                parent5.hash -> UnknownBlock(Set(hashedBlocks(6).proofsHash)),
//                hashedBlocks.head.proofsHash -> WaitingBlock(
//                  hashedBlocks.head.signed,
//                  Set.empty
//                ),
//                hashedBlocks(1).proofsHash -> AcceptedBlock(
//                  hashedBlocks(1),
//                  InMajority,
//                  Set.empty,
//                  false
//                ),
//                hashedBlocks(4).proofsHash -> AcceptedBlock(
//                  hashedBlocks(4),
//                  InMajority,
//                  Set.empty,
//                  false
//                ),
//                hashedBlocks(5).proofsHash -> AcceptedBlock(
//                  hashedBlocks(5),
//                  InMajority,
//                  Set.empty,
//                  false
//                ),
//                hashedBlocks(6).proofsHash -> AcceptedBlock(
//                  hashedBlocks(6),
//                  InMajority,
//                  Set.empty,
//                  false
//                )
//              ),
//              snapshotBalances,
//              hashedNextSnapshot,
//              snapshotTxRefs
//            )
//          )
//    }
//  }

  test("error should be thrown when a snapshot pushed for processing is not a next one") {
    testResources.use {
      case (snapshotProcessor, sp, kp, key, _, peerId, _, _, lastSnapR, _) =>
        implicit val securityProvider = sp
        implicit val kryoPool = kp

        for {
          hashedLastSnapshot <- forAsyncKryo(
            generateSnapshot(peerId),
            key
          ).flatMap(_.hashWithSignatureCheck.map(_.toOption.get))
          hashedNextSnapshot <- forAsyncKryo(
            generateSnapshot(peerId).copy(
              ordinal = SnapshotOrdinal(12L),
              subHeight = SubHeight(0L),
              lastSnapshotHash = hashedLastSnapshot.hash
            ),
            key
          ).flatMap(_.hashWithSignatureCheck.map(_.toOption.get))
          _ <- lastSnapR.set(hashedLastSnapshot.some)
          processingResult <- snapshotProcessor
            .process(hashedNextSnapshot)
            .map(_.asRight[Throwable])
            .handleErrorWith(e => IO.pure(e.asLeft[SnapshotProcessingResult]))
          lastSnapshotAfter <- lastSnapR.get.map(_.get)
        } yield
          expect.same(
            (processingResult, lastSnapshotAfter),
            (
              Left(
                UnexpectedCaseCheckingAlignment(
                  lastSnapshotHeight,
                  lastSnapshotOrdinal,
                  lastSnapshotHeight,
                  SnapshotOrdinal(12L)
                )
              ),
              hashedLastSnapshot
            )
          )
    }
  }
}
