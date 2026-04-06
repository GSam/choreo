package choreo
package examples
package twopc

import cats.effect.IO
import cats.effect.IO.asyncForIO
import cats.syntax.all.*

import choreo.backend.Backend

/** Two-Phase Commit protocol.
  *
  * Demonstrates three new primitives working together:
  *   - Par:       both participants prepare and vote concurrently
  *   - AsyncComm: votes are sent asynchronously so neither participant blocks the other
  *   - Select:    coordinator broadcasts the commit/abort decision via a label
  */

val coord: "coord" = "coord"
val partA: "partA" = "partA"
val partB: "partB" = "partB"

def main: IO[Unit] =
  for
    backend <- Backend.local(List(coord, partA, partB))
    coordIO  = protocol.project(backend, coord)
    partAIO  = protocol.project(backend, partA)
    partBIO  = protocol.project(backend, partB)
    _       <- (coordIO, partAIO, partBIO).parTupled
  yield ()

def protocol: Choreo[IO, Unit] =
  for
    // Coordinator initiates the transaction
    txnC <- coord.locally(IO.println("[coord] Starting transaction T1") *> IO.pure("T1"))

    // Send the transaction ID to both participants
    txnA <- coord.send(txnC).to(partA)
    txnB <- coord.send(txnC).to(partB)

    // Phase 1 — Prepare: both participants vote in parallel.
    // Each uses asyncSend so the coordinator receives deferred handles
    // and neither participant blocks the other.
    (voteAHandle, voteBHandle) <- {
      val left: Choreo[IO, IO[Boolean] @@ "coord"] =
        for
          vote   <- partA.locally:
                      IO.println(s"[partA] Preparing ${txnA.!}...") *> IO.pure(true)
          handle <- partA.asyncSend(vote).to(coord)
        yield handle

      val right: Choreo[IO, IO[Boolean] @@ "coord"] =
        for
          vote   <- partB.locally:
                      IO.println(s"[partB] Preparing ${txnB.!}...") *> IO.pure(true)
          handle <- partB.asyncSend(vote).to(coord)
        yield handle

      left |*| right
    }

    // Phase 2 — Decision: coordinator awaits both votes and commits iff both agree
    decision <- coord.locally:
      for
        a <- voteAHandle.!
        b <- voteBHandle.!
        _ <- IO.println(s"[coord] Votes: partA=$a, partB=$b")
      yield a && b

    // Phase 3 — Resolution: select commit or abort, notifying participants.
    // Participants acknowledge back to the coordinator to complete the protocol.
    _ <- coord.select(decision)(
      true -> (for
        ackA <- partA.locally(IO.println("[partA] Committed") *> IO.pure("ack"))
        ackB <- partB.locally(IO.println("[partB] Committed") *> IO.pure("ack"))
        _    <- partA.send(ackA).to(coord)
        _    <- partB.send(ackB).to(coord)
        _    <- coord.locally(IO.println("[coord] All acks received, transaction committed"))
      yield ()),
      false -> (for
        ackA <- partA.locally(IO.println("[partA] Aborted") *> IO.pure("ack"))
        ackB <- partB.locally(IO.println("[partB] Aborted") *> IO.pure("ack"))
        _    <- partA.send(ackA).to(coord)
        _    <- partB.send(ackB).to(coord)
        _    <- coord.locally(IO.println("[coord] All acks received, transaction aborted"))
      yield ())
    )
  yield ()
