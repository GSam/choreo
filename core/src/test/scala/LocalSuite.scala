package choreo

import cats.syntax.all.*
import cats.effect.IO
import cats.effect.kernel.Ref
import munit.CatsEffectSuite

import scala.concurrent.duration.*

class LocalSuite extends CatsEffectSuite {

  val alice: "alice" = "alice"
  val bob: "bob"     = "bob"
  val carol: "carol" = "carol"

  // -- runLocal semantics (single-location execution) --

  test("runLocal: locally returns wrapped value") {
    val c: Choreo[IO, Int @@ "alice"] =
      alice.locally(IO.pure(42))

    c.runLocal.map { result =>
      assertEquals(unwrap[alice.type](result), 42)
    }
  }

  test("runLocal: comm forwards value") {
    val c: Choreo[IO, Int @@ "bob"] =
      for
        a <- alice.locally(IO.pure(10))
        b <- alice.send(a).to(bob)
      yield b

    c.runLocal.map { result =>
      assertEquals(unwrap[bob.type](result), 10)
    }
  }

  test("runLocal: chained locally and comm") {
    val c: Choreo[IO, String @@ "bob"] =
      for
        a <- alice.locally(IO.pure("hello"))
        b <- alice.send(a).to(bob)
        c <- bob.locally(IO.pure(b.! + " world"))
        d <- bob.send(c).to(alice)
      yield c

    c.runLocal.map { result =>
      assertEquals(unwrap[bob.type](result), "hello world")
    }
  }

  // -- EPP correctness (projected endpoints produce correct results) --

  test("EPP: local operation projected at owning location runs the effect") {
    val c: Choreo[IO, Int @@ "alice"] =
      alice.locally(IO.pure(42))

    val network = Endpoint.project(c, alice, Set(alice))

    for
      backend <- Backend.local[IO](List(alice))
      result  <- backend.runNetwork(alice)(network)
    yield assertEquals(unwrap[alice.type](result), 42)
  }

  test("EPP: local operation projected at non-owning location returns Empty") {
    val c: Choreo[IO, Int @@ "alice"] =
      alice.locally(IO.pure(42))

    val network = Endpoint.project(c, bob, Set(alice, bob))

    for
      backend <- Backend.local[IO](List(alice, bob))
      result  <- backend.runNetwork(bob)(network)
    yield assert(result.isInstanceOf[At.Empty[?, ?]])
  }

  test("EPP: comm projected at both endpoints transfers value") {
    val c: Choreo[IO, Int @@ "bob"] =
      for
        a <- alice.locally(IO.pure(99))
        b <- alice.send(a).to(bob)
      yield b

    for
      backend <- Backend.local[IO](List(alice, bob))
      aliceNet = Endpoint.project(c, alice, Set(alice, bob))
      bobNet   = Endpoint.project(c, bob, Set(alice, bob))
      fiber   <- backend.runNetwork(alice)(aliceNet).start
      result  <- backend.runNetwork(bob)(bobNet)
      _       <- fiber.joinWithNever
    yield assertEquals(unwrap[bob.type](result), 99)
  }

  // -- LocalBackend round-trip communication --

  test("LocalBackend: round-trip send and receive") {
    val c: Choreo[IO, String @@ "alice"] =
      for
        a <- alice.locally(IO.pure("ping"))
        b <- alice.send(a).to(bob)
        c <- bob.locally(IO.pure(b.! + "-pong"))
        d <- bob.send(c).to(alice)
      yield d

    for
      backend  <- Backend.local[IO](List(alice, bob))
      aliceFib <- c.project(backend, alice).start
      bobFib   <- c.project(backend, bob).start
      resultA  <- aliceFib.joinWithNever
      _        <- bobFib.joinWithNever
    yield assertEquals(unwrap[alice.type](resultA), "ping-pong")
  }

  test("LocalBackend: messages from different senders are routed correctly") {
    val c: Choreo[IO, (fromAlice: Int @@ "carol", fromBob: Int @@ "carol")] =
      for
        a  <- alice.locally(IO.pure(1))
        b  <- bob.locally(IO.pure(2))
        ca <- alice.send(a).to(carol)
        cb <- bob.send(b).to(carol)
      yield (fromAlice = ca, fromBob = cb)

    for
      backend  <- Backend.local[IO](List(alice, bob, carol))
      aliceFib <- c.project(backend, alice).start
      bobFib   <- c.project(backend, bob).start
      resultC  <- c.project(backend, carol)
      _        <- aliceFib.joinWithNever
      _        <- bobFib.joinWithNever
    yield {
      assertEquals(unwrap[carol.type](resultC.fromAlice), 1)
      assertEquals(unwrap[carol.type](resultC.fromBob), 2)
    }
  }

  // -- Cond branching --

  test("Cond: true branch is taken") {
    val c: Choreo[IO, String @@ "bob"] =
      for
        flag   <- alice.locally(IO.pure(true))
        result <- alice.cond(flag) {
                    case true  => bob.locally(IO.pure("yes"))
                    case false => bob.locally(IO.pure("no"))
                  }
      yield result

    for
      backend  <- Backend.local[IO](List(alice, bob))
      aliceFib <- c.project(backend, alice).start
      resultB  <- c.project(backend, bob)
      _        <- aliceFib.joinWithNever
    yield assertEquals(unwrap[bob.type](resultB), "yes")
  }

  test("Cond: false branch is taken") {
    val c: Choreo[IO, String @@ "bob"] =
      for
        flag   <- alice.locally(IO.pure(false))
        result <- alice.cond(flag) {
                    case true  => bob.locally(IO.pure("yes"))
                    case false => bob.locally(IO.pure("no"))
                  }
      yield result

    for
      backend  <- Backend.local[IO](List(alice, bob))
      aliceFib <- c.project(backend, alice).start
      resultB  <- c.project(backend, bob)
      _        <- aliceFib.joinWithNever
    yield assertEquals(unwrap[bob.type](resultB), "no")
  }

  test("Cond: runLocal takes the correct branch") {
    val c: Choreo[IO, String @@ "bob"] =
      for
        flag   <- alice.locally(IO.pure(true))
        result <- alice.cond(flag) {
                    case true  => bob.locally(IO.pure("yes"))
                    case false => bob.locally(IO.pure("no"))
                  }
      yield result

    c.runLocal.map { result =>
      assertEquals(unwrap[bob.type](result), "yes")
    }
  }

  // -- Error cases --

  test("unwrap Empty value throws an error") {
    val empty = At.empty[Int, "alice"]
    interceptMessage[RuntimeException]("Attempted to unwrap an Empty value") {
      unwrap[alice.type](empty)
    }
  }

  test("LocalBackend: missing channel throws an error") {
    val c: Choreo[IO, Int @@ "bob"] =
      for
        a <- alice.locally(IO.pure(42))
        b <- alice.send(a).to(bob)
      yield b

    for
      backend <- Backend.local[IO](List(alice))
      result  <- c.project(backend, alice).attempt
    yield assert(result.isLeft)
  }

  // -- 3+ participant Cond --

  test("Cond: 3 participants, non-involved party receives broadcast") {
    val c: Choreo[IO, String @@ "bob"] =
      for
        flag   <- alice.locally(IO.pure(true))
        result <- alice.cond(flag) {
                    case true  => bob.locally(IO.pure("yes"))
                    case false => bob.locally(IO.pure("no"))
                  }
      yield result

    for
      backend  <- Backend.local[IO](List(alice, bob, carol))
      aliceFib <- c.project(backend, alice).start
      carolFib <- c.project(backend, carol).start
      resultB  <- c.project(backend, bob)
      _        <- aliceFib.joinWithNever
      _        <- carolFib.joinWithNever
    yield assertEquals(unwrap[bob.type](resultB), "yes")
  }

  test("Cond: 3 participants, branch involves only two of three") {
    val c: Choreo[IO, Int @@ "alice"] =
      for
        flag   <- alice.locally(IO.pure(true))
        result <- alice.cond(flag) {
                    case true  =>
                      for
                        v <- bob.locally(IO.pure(42))
                        r <- bob.send(v).to(alice)
                      yield r
                    case false =>
                      alice.locally(IO.pure(0))
                  }
      yield result

    for
      backend  <- Backend.local[IO](List(alice, bob, carol))
      aliceFib <- c.project(backend, alice).start
      bobFib   <- c.project(backend, bob).start
      carolFib <- c.project(backend, carol).start
      resultA  <- aliceFib.joinWithNever
      _        <- bobFib.joinWithNever
      _        <- carolFib.joinWithNever
    yield assertEquals(unwrap[alice.type](resultA), 42)
  }

  test("Cond: 3 participants with effects, only involved parties run effects") {
    for
      aliceLog <- Ref.of[IO, List[String]](Nil)
      bobLog   <- Ref.of[IO, List[String]](Nil)
      carolLog <- Ref.of[IO, List[String]](Nil)

      choreo =
        for
          flag   <- alice.locally:
                      aliceLog.update(_ :+ "alice:decide") *> IO.pure(true)
          result <- alice.cond(flag) {
                      case true  =>
                        for
                          v <- bob.locally:
                                 bobLog.update(_ :+ "bob:compute") *> IO.pure("ok")
                          r <- bob.send(v).to(alice)
                          _ <- alice.locally:
                                 aliceLog.update(_ :+ "alice:receive")
                          _ <- carol.locally:
                                 carolLog.update(_ :+ "carol:should-not-run")
                        yield r
                      case false =>
                        alice.locally(IO.pure("nope"))
                    }
        yield result

      backend  <- Backend.local[IO](List(alice, bob, carol))
      aliceFib <- choreo.project(backend, alice).start
      bobFib   <- choreo.project(backend, bob).start
      carolFib <- choreo.project(backend, carol).start
      _        <- aliceFib.joinWithNever
      _        <- bobFib.joinWithNever
      _        <- carolFib.joinWithNever

      aLog <- aliceLog.get
      bLog <- bobLog.get
      cLog <- carolLog.get
    yield {
      assertEquals(aLog, List("alice:decide", "alice:receive"))
      assertEquals(bLog, List("bob:compute"))
      // Carol IS mentioned in the branch (carol.locally), so knowledge of choice
      // correctly identifies her as involved — her locally block runs.
      assertEquals(cLog, List("carol:should-not-run"))
    }
  }

  test("Cond: knowledge of choice excludes truly uninvolved party") {
    for
      aliceLog <- Ref.of[IO, List[String]](Nil)
      bobLog   <- Ref.of[IO, List[String]](Nil)
      carolLog <- Ref.of[IO, List[String]](Nil)

      choreo =
        for
          flag   <- alice.locally:
                      aliceLog.update(_ :+ "alice:decide") *> IO.pure(true)
          result <- alice.cond(flag) {
                      case true  =>
                        for
                          v <- bob.locally:
                                 bobLog.update(_ :+ "bob:compute") *> IO.pure("ok")
                          r <- bob.send(v).to(alice)
                          _ <- alice.locally:
                                 aliceLog.update(_ :+ "alice:receive")
                        yield r
                      case false =>
                        alice.locally(IO.pure("nope"))
                    }
        yield result

      backend  <- Backend.local[IO](List(alice, bob, carol))
      aliceFib <- choreo.project(backend, alice).start
      bobFib   <- choreo.project(backend, bob).start
      carolFib <- choreo.project(backend, carol).start
      _        <- aliceFib.joinWithNever
      _        <- bobFib.joinWithNever
      _        <- carolFib.joinWithNever

      aLog <- aliceLog.get
      bLog <- bobLog.get
      cLog <- carolLog.get
    yield {
      assertEquals(aLog, List("alice:decide", "alice:receive"))
      assertEquals(bLog, List("bob:compute"))
      // Carol is NOT mentioned in any branch — knowledge of choice
      // correctly identifies her as uninvolved, so she skips projection.
      assertEquals(cLog, Nil)
    }
  }

  // -- Nested Cond --

  test("Cond: nested cond selects correct inner branch") {
    val c: Choreo[IO, String @@ "bob"] =
      for
        outer  <- alice.locally(IO.pure(true))
        result <- alice.cond(outer) {
                    case true  =>
                      for
                        inner <- alice.locally(IO.pure(false))
                        r     <- alice.cond(inner) {
                                   case true  => bob.locally(IO.pure("inner-true"))
                                   case false => bob.locally(IO.pure("inner-false"))
                                 }
                      yield r
                    case false =>
                      bob.locally(IO.pure("outer-false"))
                  }
      yield result

    for
      backend  <- Backend.local[IO](List(alice, bob))
      aliceFib <- c.project(backend, alice).start
      resultB  <- c.project(backend, bob)
      _        <- aliceFib.joinWithNever
    yield assertEquals(unwrap[bob.type](resultB), "inner-false")
  }

  test("Cond: nested cond with 3 participants") {
    val c: Choreo[IO, Int @@ "carol"] =
      for
        outer  <- alice.locally(IO.pure(true))
        result <- alice.cond(outer) {
                    case true  =>
                      for
                        v     <- bob.locally(IO.pure(10))
                        vC    <- bob.send(v).to(carol)
                        inner <- carol.locally(IO.pure(vC.! > 5))
                        r     <- carol.cond(inner) {
                                   case true  => carol.locally(IO.pure(100))
                                   case false => carol.locally(IO.pure(0))
                                 }
                      yield r
                    case false =>
                      carol.locally(IO.pure(-1))
                  }
      yield result

    for
      backend  <- Backend.local[IO](List(alice, bob, carol))
      aliceFib <- c.project(backend, alice).start
      bobFib   <- c.project(backend, bob).start
      resultC  <- c.project(backend, carol)
      _        <- aliceFib.joinWithNever
      _        <- bobFib.joinWithNever
    yield assertEquals(unwrap[carol.type](resultC), 100)
  }

  // -- Error propagation --

  test("distributed: error in locally propagates to the failing fiber") {
    val c: Choreo[IO, Int @@ "bob"] =
      for
        a <- alice.locally[IO, Int](IO.raiseError(new RuntimeException("boom")))
        b <- alice.send(a).to(bob)
      yield b

    for
      backend <- Backend.local[IO](List(alice, bob))
      result  <- c.project(backend, alice).attempt
    yield {
      assert(result.isLeft)
      assertEquals(result.left.toOption.get.getMessage, "boom")
    }
  }

  test("distributed: error in locally leaves other participants hanging") {
    val c: Choreo[IO, Int @@ "bob"] =
      for
        a <- alice.locally[IO, Int](IO.raiseError(new RuntimeException("boom")))
        b <- alice.send(a).to(bob)
      yield b

    for
      backend   <- Backend.local[IO](List(alice, bob))
      aliceFib  <- c.project(backend, alice).start
      // Bob will block forever on recv since alice never sends;
      // verify it doesn't complete within a timeout
      bobResult <- c.project(backend, bob).timeout(100.millis).attempt
      aliceRes  <- aliceFib.joinWithNever.attempt
    yield {
      assert(aliceRes.isLeft, "alice should have failed")
      assert(bobResult.isLeft, "bob should have timed out")
    }
  }

  // -- Deadlock --

  test("distributed: mutual recv deadlocks (detected via timeout)") {
    // Both alice and bob try to receive from each other simultaneously
    // without sending first — this is a deadlock
    val c: Choreo[IO, Int @@ "bob"] =
      for
        a <- alice.locally(IO.pure(1))
        // Alice sends to bob, but the choreography below is mis-constructed:
        // bob tries to send to alice before receiving from alice
        b <- bob.locally(IO.pure(2))
        x <- bob.send(b).to(alice)
        y <- alice.send(a).to(bob)
      yield y

    for
      backend <- Backend.local[IO](List(alice, bob))
      // In the projected programs:
      // - alice: locally(1), recv(bob), send(1, bob)  -- blocks on recv(bob)
      // - bob:   locally(2), send(2, alice), recv(alice) -- sends, then blocks on recv(alice)
      // Alice blocks waiting for bob's message before sending her own,
      // but bob sends first then waits, so this actually works for 2 parties.
      // Let's construct a true deadlock instead:
      result  <- {
        // Construct networks manually to force a deadlock:
        // both sides recv before send
        val aliceNet: Network[IO, Int] =
          for
            _ <- Network.recv[IO, Int](bob)
            _ <- Network.send[IO, Int](1, bob)
          yield 1

        val bobNet: Network[IO, Int] =
          for
            _ <- Network.recv[IO, Int](alice)
            _ <- Network.send[IO, Int](2, alice)
          yield 2

        val run = for
          aliceFib <- backend.runNetwork(alice)(aliceNet).start
          bobFib   <- backend.runNetwork(bob)(bobNet).start
          a        <- aliceFib.joinWithNever
          b        <- bobFib.joinWithNever
        yield (a, b)

        run.timeout(200.millis).attempt
      }
    yield assert(result.isLeft, "should have deadlocked (timed out)")
  }
}
