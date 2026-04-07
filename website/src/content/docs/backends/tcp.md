---
title: TCP Backend
sidebar:
  order: 2
---

The TCP backend runs participants over TCP socket connections, enabling
distributed execution across processes or machines. Messages are serialized
using a pluggable `WireCodec` and transmitted as length-prefixed binary frames.

## How it works

`TcpBackend` establishes a full mesh of TCP connections between all locations.
Each location gets its own server socket, and connections are created eagerly
for every `(sender, receiver)` pair at startup.

When a message arrives on a connection, a background reader fiber deserializes
it and places it into the receiving location's inbox queue. The network
operations map as follows:

| Network operation | Interpretation |
|-------------------|----------------|
| `Run(ma)` | Execute `ma` directly |
| `Send(a, to)` | Serialize `a`, write length-prefixed frame to socket |
| `Recv(from)` | `inbox((from, to = at)).take` |
| `Broadcast(a)` | Send to every other location |
| `Par(left, right)` | `Async[M].both(left, right)` |

Sends are synchronized on the `DataOutputStream` to ensure thread safety when
multiple fibers send to the same destination concurrently.

## Wire protocol

Messages are transmitted as length-prefixed binary frames:

```
[4 bytes: length N][N bytes: serialized payload]
```

The initial handshake also uses this format -- when a client connects, it sends
its location name as a length-prefixed UTF-8 string so the server can identify
which channel the connection belongs to.

## WireCodec

Serialization is handled by the `WireCodec` trait:

```scala
trait WireCodec:
  def encode(value: Any): Array[Byte]
  def decode(bytes: Array[Byte]): Any
```

The default implementation uses Java serialization:

```scala
WireCodec.javaSerialization
```

You can provide a custom codec (e.g., based on Protocol Buffers, JSON, or
MessagePack) by implementing the `WireCodec` trait and passing it to the
factory method.

## Effect constraint

`TcpBackend` requires `Async[M]` from Cats Effect, which is stronger than the
`Concurrent[M]` required by `LocalBackend`. The `Async` constraint is needed for
blocking socket I/O wrapped in `Async[M].blocking`.

## Creating a TCP backend

The factory method returns a `Resource` that manages the lifecycle of all sockets,
connections, and background fibers:

```scala
import choreo.backend.TcpBackend

val backend: Resource[IO, TcpBackend[IO]] =
  TcpBackend.local[IO](
    locs = List("alice", "bob"),
    codec = WireCodec.javaSerialization  // default
  )
```

The full signature:

```scala
object TcpBackend:
  def local[M[_]: Async](
      locs: List[Loc],
      codec: WireCodec = WireCodec.javaSerialization
  ): Resource[M, TcpBackend[M]]
```

Because the backend is a `Resource`, all sockets and fibers are cleaned up
automatically when the resource is released.

## Resource lifecycle

The `TcpBackend.local` factory performs these steps inside the `Resource`:

1. **Bind server sockets** -- One `ServerSocket` per location, bound to
   `localhost` on an OS-assigned port (port 0).

2. **Create inbox queues** -- One unbounded `Queue[M, Any]` per directed channel.

3. **Start acceptor fibers** -- Background fibers that accept incoming connections,
   read the sender's identity from the handshake, and spawn reader loops that
   continuously deserialize incoming messages into the appropriate inbox.

4. **Connect to peers** -- Each location connects to every other location's server
   socket and sends its identity.

5. **Wait for readiness** -- A `Deferred` gate ensures all acceptors have finished
   before the backend is returned.

When the `Resource` is released, all sockets are closed and background fibers
are canceled.

## Full example

```scala
import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all.*
import choreo.*
import choreo.backend.TcpBackend

val alice: "alice" = "alice"
val bob: "bob"     = "bob"

val greet: Choreo[IO, Unit] =
  for
    msgA <- alice.locally(IO.pure("Hello over TCP!"))
    msgB <- alice.send(msgA).to(bob)
    _    <- bob.locally(IO.println(msgB.!))
  yield ()

val main: IO[Unit] =
  TcpBackend.local[IO](List(alice, bob)).use { backend =>
    val aliceIO = greet.project(backend, alice)
    val bobIO   = greet.project(backend, bob)
    (aliceIO, bobIO).parTupled.void
  }
```

## When to use

- **Distributed deployment** -- Run each participant in its own process or on
  a different machine.
- **Realistic integration testing** -- Verify that choreography logic works
  with actual network communication and serialization.
- **Multi-JVM setups** -- Each JVM runs one participant's projected network program
  against a shared TCP backend.

Note that the current `TcpBackend.local` factory binds all server sockets on
`localhost`, which is designed for single-machine or testing scenarios. For true
multi-machine deployments, you would configure the socket addresses accordingly.
