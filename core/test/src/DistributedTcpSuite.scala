package choreo

import cats.syntax.all.*
import cats.effect.{IO, Resource}
import cats.effect.kernel.Ref
import munit.CatsEffectSuite

import java.net.{InetSocketAddress, ServerSocket}

import choreo.backend.TcpBackend

class DistributedTcpSuite extends CatsEffectSuite {

  val alice: "alice" = "alice"
  val bob: "bob"     = "bob"
  val carol: "carol" = "carol"

  /** Find a free ephemeral port by briefly binding and releasing a server socket. */
  private def freePort: IO[Int] = IO.blocking {
    val ss = new ServerSocket(0)
    val p  = ss.getLocalPort
    ss.close()
    p
  }

  private def localAddresses(locs: List[Loc]): IO[Map[Loc, InetSocketAddress]] =
    locs.traverse(l => freePort.map(p => l -> new InetSocketAddress("localhost", p))).map(_.toMap)

  test("Distributed TCP: round-trip send and receive") {
    val c: Choreo[IO, String @@ "alice"] =
      for
        a <- alice.locally(IO.pure("ping"))
        b <- alice.send(a).to(bob)
        c <- bob.locally(IO.pure(b.! + "-pong"))
        d <- bob.send(c).to(alice)
      yield d

    for
      addrs <- localAddresses(List(alice, bob))
      result <- Resource.both(
        TcpBackend.distributed[IO](addrs, alice),
        TcpBackend.distributed[IO](addrs, bob)
      ).use { (backendA, backendB) =>
        for
          aliceFib <- c.project(backendA, alice).start
          bobRes   <- c.project(backendB, bob)
          resultA  <- aliceFib.joinWithNever
        yield (resultA, bobRes)
      }
    yield assertEquals(unwrap[alice.type](result._1), "ping-pong")
  }

  test("Distributed TCP: multiple rounds of communication") {
    val pingPong: Choreo[IO, Int @@ "bob"] =
      for
        a <- alice.locally(IO.pure(1))
        b <- alice.send(a).to(bob)
        c <- bob.locally(IO.pure(b.! + 10))
        d <- bob.send(c).to(alice)
        e <- alice.locally(IO.pure(d.! + 100))
        f <- alice.send(e).to(bob)
        g <- bob.locally(IO.pure(f.! + 1000))
      yield g

    for
      addrs <- localAddresses(List(alice, bob))
      result <- Resource.both(
        TcpBackend.distributed[IO](addrs, alice),
        TcpBackend.distributed[IO](addrs, bob)
      ).use { (backendA, backendB) =>
        for
          aliceFib <- pingPong.project(backendA, alice).start
          bobRes   <- pingPong.project(backendB, bob)
          _        <- aliceFib.joinWithNever
        yield bobRes
      }
    yield assertEquals(unwrap[bob.type](result), 1111)
  }

  test("Distributed TCP: cond broadcasts decision to involved parties") {
    val c: Choreo[IO, String @@ "bob"] =
      for
        flag   <- alice.locally(IO.pure(true))
        result <- alice.cond(flag) {
                    case true  => bob.locally(IO.pure("yes"))
                    case false => bob.locally(IO.pure("no"))
                  }
      yield result

    for
      addrs <- localAddresses(List(alice, bob))
      result <- Resource.both(
        TcpBackend.distributed[IO](addrs, alice),
        TcpBackend.distributed[IO](addrs, bob)
      ).use { (backendA, backendB) =>
        for
          aliceFib <- c.project(backendA, alice).start
          bobRes   <- c.project(backendB, bob)
          _        <- aliceFib.joinWithNever
        yield bobRes
      }
    yield assertEquals(unwrap[bob.type](result), "yes")
  }

  test("Distributed TCP: 3 participants with cond") {
    val c: Choreo[IO, Int @@ "carol"] =
      for
        a      <- alice.locally(IO.pure(10))
        aB     <- alice.send(a).to(bob)
        b      <- bob.locally(IO.pure(aB.! * 2))
        bC     <- bob.send(b).to(carol)
        flag   <- carol.locally(IO.pure(bC.! > 15))
        result <- carol.cond(flag) {
                    case true  => carol.locally(IO.pure(bC.!))
                    case false => carol.locally(IO.pure(0))
                  }
      yield result

    for
      addrs <- localAddresses(List(alice, bob, carol))
      result <- Resource.both(
        TcpBackend.distributed[IO](addrs, alice),
        Resource.both(
          TcpBackend.distributed[IO](addrs, bob),
          TcpBackend.distributed[IO](addrs, carol)
        )
      ).use { case (backendA, (backendB, backendC)) =>
        for
          aliceFib <- c.project(backendA, alice).start
          bobFib   <- c.project(backendB, bob).start
          carolRes <- c.project(backendC, carol)
          _        <- aliceFib.joinWithNever
          _        <- bobFib.joinWithNever
        yield carolRes
      }
    yield assertEquals(unwrap[carol.type](result), 20)
  }

  test("Distributed TCP: effects only run at the owning location") {
    for
      buyerLog  <- Ref.of[IO, List[String]](Nil)
      sellerLog <- Ref.of[IO, List[String]](Nil)

      buyer: "buyer"   = "buyer"
      seller: "seller" = "seller"

      choreo =
        for
          titleB   <- buyer.locally:
                        buyerLog.update(_ :+ "buyer:title") *> IO.pure("hello")
          titleS   <- buyer.send(titleB).to(seller)
          priceS   <- seller.locally:
                        sellerLog.update(_ :+ "seller:price") *> IO.pure(80.0)
          priceB   <- seller.send(priceS).to(buyer)
          decision <- buyer.locally:
                        buyerLog.update(_ :+ "buyer:decision") *> IO.pure(true)
          result   <- buyer.select(decision)(
                        true  -> (for
                          dateS <- seller.locally:
                                     sellerLog.update(_ :+ "seller:date") *> IO.pure("2026-01-01")
                          dateB <- seller.send(dateS).to(buyer)
                        yield Some(dateB)),
                        false -> Choreo.pure(None)
                      )
        yield result

      addrs <- localAddresses(List(buyer, seller))

      _ <- Resource.both(
        TcpBackend.distributed[IO](addrs, buyer),
        TcpBackend.distributed[IO](addrs, seller)
      ).use { (backendBuyer, backendSeller) =>
        for
          sellerF <- choreo.project(backendSeller, seller).start
          _       <- choreo.project(backendBuyer, buyer)
          _       <- sellerF.joinWithNever
        yield ()
      }

      bLog <- buyerLog.get
      sLog <- sellerLog.get
    yield {
      assertEquals(bLog, List("buyer:title", "buyer:decision"))
      assertEquals(sLog, List("seller:price", "seller:date"))
    }
  }

  // -- Select branching --

  test("Distributed TCP: select true branch") {
    val c: Choreo[IO, String @@ "bob"] =
      for
        flag   <- alice.locally(IO.pure(true))
        result <- alice.select(flag)(
                    true  -> bob.locally(IO.pure("yes")),
                    false -> bob.locally(IO.pure("no"))
                  )
      yield result

    for
      addrs <- localAddresses(List(alice, bob))
      result <- Resource.both(
        TcpBackend.distributed[IO](addrs, alice),
        TcpBackend.distributed[IO](addrs, bob)
      ).use { (backendA, backendB) =>
        for
          aliceFib <- c.project(backendA, alice).start
          bobRes   <- c.project(backendB, bob)
          _        <- aliceFib.joinWithNever
        yield bobRes
      }
    yield assertEquals(unwrap[bob.type](result), "yes")
  }

  test("Distributed TCP: select false branch") {
    val c: Choreo[IO, String @@ "bob"] =
      for
        flag   <- alice.locally(IO.pure(false))
        result <- alice.select(flag)(
                    true  -> bob.locally(IO.pure("yes")),
                    false -> bob.locally(IO.pure("no"))
                  )
      yield result

    for
      addrs <- localAddresses(List(alice, bob))
      result <- Resource.both(
        TcpBackend.distributed[IO](addrs, alice),
        TcpBackend.distributed[IO](addrs, bob)
      ).use { (backendA, backendB) =>
        for
          aliceFib <- c.project(backendA, alice).start
          bobRes   <- c.project(backendB, bob)
          _        <- aliceFib.joinWithNever
        yield bobRes
      }
    yield assertEquals(unwrap[bob.type](result), "no")
  }

  test("Distributed TCP: select with communication in branch") {
    val c: Choreo[IO, Int @@ "alice"] =
      for
        flag   <- alice.locally(IO.pure(true))
        result <- alice.select(flag)(
                    true  -> (for
                      v <- bob.locally(IO.pure(42))
                      r <- bob.send(v).to(alice)
                    yield r),
                    false -> alice.locally(IO.pure(0))
                  )
      yield result

    for
      addrs <- localAddresses(List(alice, bob))
      result <- Resource.both(
        TcpBackend.distributed[IO](addrs, alice),
        TcpBackend.distributed[IO](addrs, bob)
      ).use { (backendA, backendB) =>
        for
          aliceFib <- c.project(backendA, alice).start
          bobRes   <- c.project(backendB, bob)
          resultA  <- aliceFib.joinWithNever
        yield resultA
      }
    yield assertEquals(unwrap[alice.type](result), 42)
  }

  // -- Par (parallel composition) --

  test("Distributed TCP: par with disjoint locations") {
    val left: Choreo[IO, Int @@ "bob"] =
      for
        a <- alice.locally(IO.pure(42))
        b <- alice.send(a).to(bob)
      yield b

    val right: Choreo[IO, String @@ "carol"] =
      carol.locally(IO.pure("done"))

    val c = left |*| right

    for
      addrs <- localAddresses(List(alice, bob, carol))
      result <- Resource.both(
        TcpBackend.distributed[IO](addrs, alice),
        Resource.both(
          TcpBackend.distributed[IO](addrs, bob),
          TcpBackend.distributed[IO](addrs, carol)
        )
      ).use { case (backendA, (backendB, backendC)) =>
        for
          aliceFib <- c.project(backendA, alice).start
          bobFib   <- c.project(backendB, bob).start
          carolRes <- c.project(backendC, carol)
          _        <- aliceFib.joinWithNever
          bobRes   <- bobFib.joinWithNever
        yield (bobRes, carolRes)
      }
    yield {
      assertEquals(unwrap[bob.type](result._1._1), 42)
      assertEquals(unwrap[carol.type](result._2._2), "done")
    }
  }

  // -- Async communication --

  test("Distributed TCP: asyncSend delivers value") {
    val c: Choreo[IO, Int @@ "bob"] =
      for
        a <- alice.locally(IO.pure(99))
        f <- alice.asyncSend(a).to(bob)
        b <- bob.locally(f.!.map(_ + 1))
      yield b

    for
      addrs <- localAddresses(List(alice, bob))
      result <- Resource.both(
        TcpBackend.distributed[IO](addrs, alice),
        TcpBackend.distributed[IO](addrs, bob)
      ).use { (backendA, backendB) =>
        for
          aliceFib <- c.project(backendA, alice).start
          bobRes   <- c.project(backendB, bob)
          _        <- aliceFib.joinWithNever
        yield bobRes
      }
    yield assertEquals(unwrap[bob.type](result), 100)
  }

  // -- Server / Connect --

  test("Server/Connect: round-trip send and receive") {
    val serverLoc: "server" = "server"
    val clientLoc: "client" = "client"

    val c: Choreo[IO, String @@ "client"] =
      for
        a <- clientLoc.locally(IO.pure("ping"))
        b <- clientLoc.send(a).to(serverLoc)
        c <- serverLoc.locally(IO.pure(b.! + "-pong"))
        d <- serverLoc.send(c).to(clientLoc)
      yield d

    for
      port <- freePort
      addr  = new InetSocketAddress("localhost", port)
      result <- (
        TcpBackend.server[IO](addr, serverLoc, clientLoc) { backend =>
          c.project(backend, serverLoc).void
        } *>
        TcpBackend.connect[IO](addr, serverLoc, clientLoc)
      ).use { clientBackend =>
        c.project(clientBackend, clientLoc)
      }
    yield assertEquals(unwrap[clientLoc.type](result), "ping-pong")
  }

  test("Server/Connect: multiple concurrent clients") {
    val serverLoc: "server" = "server"
    val clientLoc: "client" = "client"

    val c: Choreo[IO, Int @@ "client"] =
      for
        a <- clientLoc.locally(IO.pure(1))
        b <- clientLoc.send(a).to(serverLoc)
        c <- serverLoc.locally(IO.pure(b.! + 10))
        d <- serverLoc.send(c).to(clientLoc)
      yield d

    for
      port <- freePort
      addr  = new InetSocketAddress("localhost", port)
      results <- TcpBackend.server[IO](addr, serverLoc, clientLoc) { backend =>
                   c.project(backend, serverLoc).void
                 }.use { _ =>
                   val connectAndRun = TcpBackend.connect[IO](addr, serverLoc, clientLoc).use { backend =>
                     c.project(backend, clientLoc)
                   }
                   // Run 3 clients concurrently
                   (connectAndRun, connectAndRun, connectAndRun).parTupled
                 }
    yield {
      assertEquals(unwrap[clientLoc.type](results._1), 11)
      assertEquals(unwrap[clientLoc.type](results._2), 11)
      assertEquals(unwrap[clientLoc.type](results._3), 11)
    }
  }

  test("Server/Connect: select branching") {
    val serverLoc: "server" = "server"
    val clientLoc: "client" = "client"

    val c: Choreo[IO, String @@ "client"] =
      for
        flag   <- clientLoc.locally(IO.pure(true))
        result <- clientLoc.select(flag)(
                    true  -> serverLoc.locally(IO.pure("yes")),
                    false -> serverLoc.locally(IO.pure("no"))
                  )
        msg    <- serverLoc.send(result).to(clientLoc)
      yield msg

    for
      port <- freePort
      addr  = new InetSocketAddress("localhost", port)
      result <- (
        TcpBackend.server[IO](addr, serverLoc, clientLoc) { backend =>
          c.project(backend, serverLoc).void
        } *>
        TcpBackend.connect[IO](addr, serverLoc, clientLoc)
      ).use { clientBackend =>
        c.project(clientBackend, clientLoc)
      }
    yield assertEquals(unwrap[clientLoc.type](result), "yes")
  }

  test("Server/Connect: sessions are isolated") {
    val serverLoc: "server" = "server"
    val clientLoc: "client" = "client"

    def echo(n: Int): Choreo[IO, Int @@ "client"] =
      for
        a <- clientLoc.locally(IO.pure(n))
        b <- clientLoc.send(a).to(serverLoc)
        c <- serverLoc.locally(IO.pure(b.! * 2))
        d <- serverLoc.send(c).to(clientLoc)
      yield d

    for
      port <- freePort
      addr  = new InetSocketAddress("localhost", port)
      results <- TcpBackend.server[IO](addr, serverLoc, clientLoc) { backend =>
                   // Server doesn't know which value n is — each session is independent
                   echo(0).project(backend, serverLoc).void
                 }.use { _ =>
                   val client1 = TcpBackend.connect[IO](addr, serverLoc, clientLoc).use { b =>
                     echo(5).project(b, clientLoc)
                   }
                   val client2 = TcpBackend.connect[IO](addr, serverLoc, clientLoc).use { b =>
                     echo(7).project(b, clientLoc)
                   }
                   (client1, client2).parTupled
                 }
    yield {
      assertEquals(unwrap[clientLoc.type](results._1), 10)
      assertEquals(unwrap[clientLoc.type](results._2), 14)
    }
  }
}
