package choreo

import cats.syntax.all.*
import cats.effect.IO
import cats.effect.kernel.Ref
import munit.CatsEffectSuite

import choreo.backend.TcpBackend

case class DeliveryDate(year: Int, month: Int, day: Int)

class TcpSuite extends CatsEffectSuite {

  val alice: "alice" = "alice"
  val bob: "bob"     = "bob"
  val carol: "carol" = "carol"

  test("TCP: round-trip send and receive") {
    val c: Choreo[IO, String @@ "alice"] =
      for
        a <- alice.locally(IO.pure("ping"))
        b <- alice.send(a).to(bob)
        c <- bob.locally(IO.pure(b.! + "-pong"))
        d <- bob.send(c).to(alice)
      yield d

    TcpBackend.local[IO](List(alice, bob)).use { backend =>
      for
        aliceFib <- c.project(backend, alice).start
        bobFib   <- c.project(backend, bob).start
        resultA  <- aliceFib.joinWithNever
        _        <- bobFib.joinWithNever
      yield assertEquals(unwrap[alice.type](resultA), "ping-pong")
    }
  }

  test("TCP: multiple rounds of communication") {
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

    TcpBackend.local[IO](List(alice, bob)).use { backend =>
      for
        aliceFib <- pingPong.project(backend, alice).start
        bobRes   <- pingPong.project(backend, bob)
        _        <- aliceFib.joinWithNever
      yield assertEquals(unwrap[bob.type](bobRes), 1111)
    }
  }

  test("TCP: cond broadcasts decision to involved parties") {
    val c: Choreo[IO, String @@ "bob"] =
      for
        flag   <- alice.locally(IO.pure(true))
        result <- alice.cond(flag) {
                    case true  => bob.locally(IO.pure("yes"))
                    case false => bob.locally(IO.pure("no"))
                  }
      yield result

    TcpBackend.local[IO](List(alice, bob)).use { backend =>
      for
        aliceFib <- c.project(backend, alice).start
        resultB  <- c.project(backend, bob)
        _        <- aliceFib.joinWithNever
      yield assertEquals(unwrap[bob.type](resultB), "yes")
    }
  }

  test("TCP: 3 participants with cond") {
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

    TcpBackend.local[IO](List(alice, bob, carol)).use { backend =>
      for
        aliceFib <- c.project(backend, alice).start
        bobFib   <- c.project(backend, bob).start
        resultC  <- c.project(backend, carol)
        _        <- aliceFib.joinWithNever
        _        <- bobFib.joinWithNever
      yield assertEquals(unwrap[carol.type](resultC), 20)
    }
  }

  test("TCP: effects only run at owning location") {
    TcpBackend.local[IO](List(alice, bob)).use { backend =>
      for
        aliceLog <- Ref.of[IO, List[String]](Nil)
        bobLog   <- Ref.of[IO, List[String]](Nil)

        choreo =
          for
            a <- alice.locally:
                   aliceLog.update(_ :+ "alice:local") *> IO.pure(42)
            b <- alice.send(a).to(bob)
            c <- bob.locally:
                   bobLog.update(_ :+ "bob:local") *> IO.pure(b.! + 1)
          yield c

        aliceFib <- choreo.project(backend, alice).start
        bobRes   <- choreo.project(backend, bob)
        _        <- aliceFib.joinWithNever

        aLog <- aliceLog.get
        bLog <- bobLog.get
      yield {
        assertEquals(aLog, List("alice:local"))
        assertEquals(bLog, List("bob:local"))
        assertEquals(unwrap[bob.type](bobRes), 43)
      }
    }
  }

  test("TCP: bookseller protocol end-to-end") {
    val buyer: "buyer"   = "buyer"
    val seller: "seller" = "seller"

    def bookseller(
        title: String,
        budget: Double
    ): Choreo[IO, Option[DeliveryDate @@ "buyer"]] =
      for
        titleB <- buyer.locally(IO.pure(title))
        titleS <- buyer.send(titleB).to(seller)

        priceS <- seller.locally(IO.pure(if titleS.! == "TAPL" then 80.0 else 0.0))
        priceB <- seller.send(priceS).to(buyer)

        decision <- buyer.locally(IO.pure(priceB.! > 0 && priceB.! <= budget))

        result <- buyer.select(decision)(
                    true  -> (for
                      dateS <- seller.locally(IO.pure(DeliveryDate(2026, 6, 15)))
                      dateB <- seller.send(dateS).to(buyer)
                    yield Some(dateB)),
                    false -> Choreo.pure(None)
                  )
      yield result

    TcpBackend.local[IO](List(buyer, seller)).use { backend =>
      val choreo = bookseller("TAPL", 100.0)
      for
        sellerFib <- choreo.project(backend, seller).start
        buyerRes  <- choreo.project(backend, buyer)
        _         <- sellerFib.joinWithNever
      yield {
        val date = unwrap[buyer.type](buyerRes.get)
        assertEquals(date, DeliveryDate(2026, 6, 15))
      }
    }
  }
}
