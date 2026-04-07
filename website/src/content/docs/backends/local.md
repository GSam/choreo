---
title: Local Backend
sidebar:
  order: 1
---

The local backend runs all participants in a single process using in-memory
queues for communication. It is ideal for testing, prototyping, and demos where
you want to run a choreography without any network overhead.

## How it works

`LocalBackend` creates one bounded queue per directed channel between locations.
For *n* locations there are *n * (n - 1)* queues, one for each `(sender, receiver)` pair.

When interpreting a `Network` program, each operation maps to a queue action:

| Network operation | Interpretation |
|-------------------|----------------|
| `Run(ma)` | Execute `ma` directly |
| `Send(a, to)` | `queue((from = at, to)).offer(a)` |
| `Recv(from)` | `queue((from, to = at)).take` |
| `Broadcast(a)` | `offer(a)` to every other location's queue |
| `Par(left, right)` | `Concurrent[M].both(left, right)` |

Messages are untyped (`Queue[M, Any]`) at the transport level. Type safety is
guaranteed by the choreography's type system -- the types in `A @@ L` ensure that
only the correct location can unwrap a value, and the projection rules ensure
matching send/receive pairs.

## Effect constraint

`LocalBackend` requires `Concurrent[M]` from Cats Effect:

- Queues (`Queue[M, Any]`) require `Concurrent` for creation and access.
- `Par` uses `Concurrent[M].both` to run sub-programs concurrently.

In practice this means any `IO`-based or `cats.effect.IOLocal`-based effect will work.

## Creating a local backend

```scala
import choreo.backend.Backend

val program: IO[Unit] =
  for
    backend <- Backend.local(List("alice", "bob", "carol"))
    // backend: LocalBackend[IO]
    // ...
  yield ()
```

`Backend.local` is an alias for `LocalBackend.apply`, which has the signature:

```scala
object LocalBackend:
  def apply[M[_]: Concurrent](locs: Seq[Loc]): M[LocalBackend[M]]
```

The factory allocates all queues in `M` and returns the backend wrapped in the
effect. The list of locations determines which channels are created.

## Full example

```scala
import cats.effect.IO
import cats.syntax.all.*
import choreo.*
import choreo.backend.Backend

val alice: "alice" = "alice"
val bob: "bob"     = "bob"

val greet: Choreo[IO, Unit] =
  for
    msgA <- alice.locally(IO.pure("Hello from Alice!"))
    msgB <- alice.send(msgA).to(bob)
    _    <- bob.locally(IO.println(msgB.!))
  yield ()

val main: IO[Unit] =
  for
    backend  <- Backend.local(List(alice, bob))
    aliceIO   = greet.project(backend, alice)
    bobIO     = greet.project(backend, bob)
    _        <- (aliceIO, bobIO).parTupled
  yield ()
```

## When to use

- **Unit and integration tests** -- Fast, deterministic, no network setup.
- **Interactive demos** -- Run all participants in one JVM with console I/O.
- **Prototyping** -- Validate choreography logic before deploying with a real
  transport backend.

Because all communication happens through in-memory queues, there is no
serialization overhead and no risk of network errors, making the local backend
the simplest way to verify that a choreography behaves correctly.
