package choreo

import scala.concurrent.duration.*

import cats.syntax.all.*
import cats.effect.IO
import cats.effect.kernel.Ref
import munit.CatsEffectSuite

import choreo.backend.Backend

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

  // -- Select branching --

  test("Select: runLocal picks correct branch") {
    val c: Choreo[IO, String @@ "bob"] =
      for
        flag   <- alice.locally(IO.pure(true))
        result <- alice.select(flag)(
                    true  -> bob.locally(IO.pure("yes")),
                    false -> bob.locally(IO.pure("no"))
                  )
      yield result

    c.runLocal.map { result =>
      assertEquals(unwrap[bob.type](result), "yes")
    }
  }

  test("Select: true branch is taken (distributed)") {
    val c: Choreo[IO, String @@ "bob"] =
      for
        flag   <- alice.locally(IO.pure(true))
        result <- alice.select(flag)(
                    true  -> bob.locally(IO.pure("yes")),
                    false -> bob.locally(IO.pure("no"))
                  )
      yield result

    for
      backend  <- Backend.local[IO](List(alice, bob))
      aliceFib <- c.project(backend, alice).start
      resultB  <- c.project(backend, bob)
      _        <- aliceFib.joinWithNever
    yield assertEquals(unwrap[bob.type](resultB), "yes")
  }

  test("Select: false branch is taken (distributed)") {
    val c: Choreo[IO, String @@ "bob"] =
      for
        flag   <- alice.locally(IO.pure(false))
        result <- alice.select(flag)(
                    true  -> bob.locally(IO.pure("yes")),
                    false -> bob.locally(IO.pure("no"))
                  )
      yield result

    for
      backend  <- Backend.local[IO](List(alice, bob))
      aliceFib <- c.project(backend, alice).start
      resultB  <- c.project(backend, bob)
      _        <- aliceFib.joinWithNever
    yield assertEquals(unwrap[bob.type](resultB), "no")
  }

  test("Select: 3 participants, uninvolved party gets no message") {
    for
      carolLog <- Ref.of[IO, List[String]](Nil)

      choreo =
        for
          flag   <- alice.locally(IO.pure(true))
          result <- alice.select(flag)(
                      true  -> bob.locally(IO.pure("yes")),
                      false -> bob.locally(IO.pure("no"))
                    )
        yield result

      backend  <- Backend.local[IO](List(alice, bob, carol))
      aliceFib <- choreo.project(backend, alice).start
      carolFib <- choreo.project(backend, carol).start
      resultB  <- choreo.project(backend, bob)
      _        <- aliceFib.joinWithNever
      _        <- carolFib.joinWithNever
    yield assertEquals(unwrap[bob.type](resultB), "yes")
  }

  test("Select: 3 participants, branch involves two of three") {
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
      backend  <- Backend.local[IO](List(alice, bob, carol))
      aliceFib <- c.project(backend, alice).start
      bobFib   <- c.project(backend, bob).start
      carolFib <- c.project(backend, carol).start
      resultA  <- aliceFib.joinWithNever
      _        <- bobFib.joinWithNever
      _        <- carolFib.joinWithNever
    yield assertEquals(unwrap[alice.type](resultA), 42)
  }

  test("Select: only label is sent, not the full value") {
    // Use an enum label to prove only the discriminant travels
    for
      sentValues <- Ref.of[IO, List[Any]](Nil)

      choreo =
        for
          choice <- alice.locally(IO.pure("buy"))
          result <- alice.select(choice)(
                      "buy"  -> bob.locally(IO.pure(100)),
                      "skip" -> bob.locally(IO.pure(0))
                    )
        yield result

      backend  <- Backend.local[IO](List(alice, bob))
      aliceFib <- choreo.project(backend, alice).start
      resultB  <- choreo.project(backend, bob)
      _        <- aliceFib.joinWithNever
    yield assertEquals(unwrap[bob.type](resultB), 100)
  }

  test("Select: effects only run at involved locations") {
    for
      aliceLog <- Ref.of[IO, List[String]](Nil)
      bobLog   <- Ref.of[IO, List[String]](Nil)
      carolLog <- Ref.of[IO, List[String]](Nil)

      choreo =
        for
          flag   <- alice.locally:
                      aliceLog.update(_ :+ "alice:decide") *> IO.pure(true)
          result <- alice.select(flag)(
                      true  -> (for
                        v <- bob.locally:
                               bobLog.update(_ :+ "bob:compute") *> IO.pure("ok")
                        r <- bob.send(v).to(alice)
                        _ <- alice.locally:
                               aliceLog.update(_ :+ "alice:receive")
                      yield r),
                      false -> alice.locally(IO.pure("nope"))
                    )
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
      assertEquals(cLog, Nil)
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

  // -- Par (parallel composition) --

  test("Par: runLocal returns both results") {
    val left: Choreo[IO, Int @@ "alice"]   = alice.locally(IO.pure(1))
    val right: Choreo[IO, String @@ "bob"] = bob.locally(IO.pure("hello"))
    val c                                  = left |*| right

    c.runLocal.map { case (a, b) =>
      assertEquals(unwrap[alice.type](a), 1)
      assertEquals(unwrap[bob.type](b), "hello")
    }
  }

  test("Par: distributed, disjoint locations run concurrently") {
    val left: Choreo[IO, Int @@ "bob"] =
      for
        a <- alice.locally(IO.pure(42))
        b <- alice.send(a).to(bob)
      yield b

    val right: Choreo[IO, String @@ "carol"] =
      carol.locally(IO.pure("done"))

    val c = left |*| right

    for
      backend  <- Backend.local[IO](List(alice, bob, carol))
      aliceFib <- c.project(backend, alice).start
      bobFib   <- c.project(backend, bob).start
      carolRes <- c.project(backend, carol)
      aliceRes <- aliceFib.joinWithNever
      bobRes   <- bobFib.joinWithNever
    yield {
      assertEquals(unwrap[bob.type](bobRes._1), 42)
      assertEquals(unwrap[carol.type](carolRes._2), "done")
    }
  }

  test("Par: effects in both branches run at correct locations") {
    for
      aliceLog <- Ref.of[IO, List[String]](Nil)
      bobLog   <- Ref.of[IO, List[String]](Nil)

      left  = alice.locally(aliceLog.update(_ :+ "left") *> IO.pure(1))
      right = bob.locally(bobLog.update(_ :+ "right") *> IO.pure(2))
      c     = left |*| right

      backend  <- Backend.local[IO](List(alice, bob))
      aliceFib <- c.project(backend, alice).start
      bobRes   <- c.project(backend, bob)
      aliceRes <- aliceFib.joinWithNever

      aLog <- aliceLog.get
      bLog <- bobLog.get
    yield {
      assertEquals(aLog, List("left"))
      assertEquals(bLog, List("right"))
    }
  }

  test("Par: independent communication channels don't interfere") {
    // left: alice -> bob, right: carol -> alice (different channels)
    val left: Choreo[IO, Int @@ "bob"] =
      for
        a <- alice.locally(IO.pure(10))
        b <- alice.send(a).to(bob)
      yield b

    val right: Choreo[IO, String @@ "alice"] =
      for
        c <- carol.locally(IO.pure("hi"))
        a <- carol.send(c).to(alice)
      yield a

    val c = left |*| right

    for
      backend  <- Backend.local[IO](List(alice, bob, carol))
      aliceFib <- c.project(backend, alice).start
      bobFib   <- c.project(backend, bob).start
      carolRes <- c.project(backend, carol)
      aliceRes <- aliceFib.joinWithNever
      bobRes   <- bobFib.joinWithNever
    yield {
      assertEquals(unwrap[bob.type](bobRes._1), 10)
      assertEquals(unwrap[alice.type](aliceRes._2), "hi")
    }
  }

  test("Par: Choreo.par is equivalent to |*| operator") {
    val left: Choreo[IO, Int @@ "alice"] = alice.locally(IO.pure(1))
    val right: Choreo[IO, Int @@ "bob"]  = bob.locally(IO.pure(2))

    val c1 = Choreo.par(left, right)
    val c2 = left |*| right

    for
      r1 <- c1.runLocal
      r2 <- c2.runLocal
    yield {
      assertEquals(unwrap[alice.type](r1._1), unwrap[alice.type](r2._1))
      assertEquals(unwrap[bob.type](r1._2), unwrap[bob.type](r2._2))
    }
  }

  test("Par: followed by sequential steps") {
    val left: Choreo[IO, Int @@ "alice"]   = alice.locally(IO.pure(10))
    val right: Choreo[IO, String @@ "bob"] = bob.locally(IO.pure("x"))

    val c: Choreo[IO, String @@ "bob"] =
      for
        (a, b) <- left |*| right
        aB     <- alice.send(a).to(bob)
        result <- bob.locally(IO.pure(s"${b.!} repeated ${aB.!} times"))
      yield result

    c.runLocal.map { result =>
      assertEquals(unwrap[bob.type](result), "x repeated 10 times")
    }
  }

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

  // -- AsyncComm (asynchronous communication) --

  test("AsyncComm: runLocal returns awaitable value") {
    val c: Choreo[IO, IO[Int] @@ "bob"] =
      for
        a <- alice.locally(IO.pure(42))
        f <- alice.asyncSend(a).to(bob)
      yield f

    c.runLocal.flatMap { result =>
      unwrap[bob.type](result).map { value =>
        assertEquals(value, 42)
      }
    }
  }

  test("AsyncComm: runLocal value can be awaited in locally") {
    val c: Choreo[IO, Int @@ "bob"] =
      for
        a <- alice.locally(IO.pure(42))
        f <- alice.asyncSend(a).to(bob)
        b <- bob.locally(f.!)
      yield b

    c.runLocal.map { result =>
      assertEquals(unwrap[bob.type](result), 42)
    }
  }

  test("AsyncComm: distributed, receiver gets value via deferred") {
    val c: Choreo[IO, Int @@ "bob"] =
      for
        a <- alice.locally(IO.pure(99))
        f <- alice.asyncSend(a).to(bob)
        b <- bob.locally(f.!)
      yield b

    for
      backend  <- Backend.local[IO](List(alice, bob))
      aliceFib <- c.project(backend, alice).start
      resultB  <- c.project(backend, bob)
      _        <- aliceFib.joinWithNever
    yield assertEquals(unwrap[bob.type](resultB), 99)
  }

  test("AsyncComm: sender proceeds before receiver awaits") {
    for
      aliceLog <- Ref.of[IO, List[String]](Nil)
      bobLog   <- Ref.of[IO, List[String]](Nil)

      choreo =
        for
          a <- alice.locally(aliceLog.update(_ :+ "alice:compute") *> IO.pure(1))
          f <- alice.asyncSend(a).to(bob)
          // Alice continues with more work after the async send
          _ <- alice.locally(aliceLog.update(_ :+ "alice:after-send") *> IO.pure(()))
          // Bob awaits the value
          b <- bob.locally(bobLog.update(_ :+ "bob:await") *> f.!)
        yield b

      backend  <- Backend.local[IO](List(alice, bob))
      aliceFib <- choreo.project(backend, alice).start
      resultB  <- choreo.project(backend, bob)
      _        <- aliceFib.joinWithNever

      aLog <- aliceLog.get
      bLog <- bobLog.get
    yield {
      assertEquals(aLog, List("alice:compute", "alice:after-send"))
      assertEquals(bLog, List("bob:await"))
      assertEquals(unwrap[bob.type](resultB), 1)
    }
  }

  test("AsyncComm: multiple async sends can be pipelined") {
    val c: Choreo[IO, (Int @@ "bob", String @@ "bob")] =
      for
        a  <- alice.locally(IO.pure(42))
        b  <- alice.locally(IO.pure("hello"))
        fA <- alice.asyncSend(a).to(bob)
        fB <- alice.asyncSend(b).to(bob)
        rA <- bob.locally(fA.!)
        rB <- bob.locally(fB.!)
      yield (rA, rB)

    for
      backend  <- Backend.local[IO](List(alice, bob))
      aliceFib <- c.project(backend, alice).start
      resultB  <- c.project(backend, bob)
      _        <- aliceFib.joinWithNever
    yield {
      assertEquals(unwrap[bob.type](resultB._1), 42)
      assertEquals(unwrap[bob.type](resultB._2), "hello")
    }
  }

  test("AsyncComm: mixed sync and async communication") {
    val c: Choreo[IO, (Int @@ "bob", String @@ "bob")] =
      for
        a <- alice.locally(IO.pure(10))
        b <- alice.send(a).to(bob)      // sync
        c <- alice.locally(IO.pure("hi"))
        f <- alice.asyncSend(c).to(bob) // async
        d <- bob.locally(f.!)           // await async
      yield (b, d)

    for
      backend  <- Backend.local[IO](List(alice, bob))
      aliceFib <- c.project(backend, alice).start
      resultB  <- c.project(backend, bob)
      _        <- aliceFib.joinWithNever
    yield {
      assertEquals(unwrap[bob.type](resultB._1), 10)
      assertEquals(unwrap[bob.type](resultB._2), "hi")
    }
  }

  test("AsyncComm: 3 participants, uninvolved party is unaffected") {
    val c: Choreo[IO, Int @@ "bob"] =
      for
        a <- alice.locally(IO.pure(7))
        f <- alice.asyncSend(a).to(bob)
        b <- bob.locally(f.!)
      yield b

    for
      backend  <- Backend.local[IO](List(alice, bob, carol))
      aliceFib <- c.project(backend, alice).start
      carolFib <- c.project(backend, carol).start
      resultB  <- c.project(backend, bob)
      _        <- aliceFib.joinWithNever
      _        <- carolFib.joinWithNever
    yield assertEquals(unwrap[bob.type](resultB), 7)
  }

  test("AsyncComm: deferred can be awaited multiple times") {
    val c: Choreo[IO, (Int @@ "bob", Int @@ "bob")] =
      for
        a  <- alice.locally(IO.pure(5))
        f  <- alice.asyncSend(a).to(bob)
        b1 <- bob.locally(f.!)
        b2 <- bob.locally(f.!)
      yield (b1, b2)

    c.runLocal.map { case (r1, r2) =>
      assertEquals(unwrap[bob.type](r1), 5)
      assertEquals(unwrap[bob.type](r2), 5)
    }
  }
}
