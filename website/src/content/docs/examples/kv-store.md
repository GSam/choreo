---
title: Key-Value Store
sidebar:
  order: 1
---

This example implements a client/server key-value store where the client issues
`GET` and `PUT` requests and the server maintains state using a `Ref`. It
demonstrates local computation, point-to-point communication, and stateful
choreographic loops.

Source: `examples/src/KV.scala`

## Locations and types

Every choreography starts by defining the participating locations and the data types
that flow between them:

```scala
val client: "client" = "client"
val server: "server" = "server"

type State = Map[String, String]

enum Request:
  case Get(key: String)
  case Put(key: String, value: String)

type Response = Option[String]
```

Locations are Scala 3 singleton string types. The type annotation `"client"` (rather
than `String`) lets the compiler track which location owns each value at the type
level.

## The main choreography

The top-level `choreo` value describes the entire protocol as a single global program:

```scala
def choreo: Choreo[IO, Unit] =
  for
    stateS <- server.locally(Ref.of[IO, State](Map.empty))
    _      <- step(stateS).foreverM
  yield ()
```

The server initializes a `Ref[IO, State]` using `server.locally`. This executes the
`IO` action only at the server and returns a value of type `Ref[IO, State] @@ "server"`
-- a `Ref` that is *located at* the server. No other participant can access it.

The choreography then loops `step` forever using `foreverM` from Cats.

## The step function

Each iteration of the loop performs one request/response cycle:

```scala
def step(stateS: Ref[IO, State] @@ "server"): Choreo[IO, Unit] =
  for
    reqC <- client.locally(readRequest)
    resC <- kvs(reqC, stateS)
    _    <- client.locally:
              resC.!.fold(IO.println("Key not found")):
                IO.print("> ") *> IO.println(_)
  yield ()
```

1. The client reads a request from stdin using `client.locally(readRequest)`.
   The result `reqC` has type `Request @@ "client"` -- a `Request` located at the client.

2. The `kvs` sub-choreography sends the request to the server, processes it, and
   returns the response back to the client.

3. The client unwraps the response with `.!` (which requires the `Unwrap["client"]`
   context provided during projection) and prints the result.

The `readRequest` function is a plain `IO` action that parses user input:

```scala
def readRequest: IO[Request] =
  for
    _    <- IO.print("> ")
    line <- IO.readLine
    req  <- line.split(" ") match
              case Array("GET", key) =>
                IO.pure(Request.Get(key))
              case Array("PUT", key, value) =>
                IO.pure(Request.Put(key, value))
              case _ =>
                IO.raiseError(new Exception("Invalid request"))
  yield req
```

## The kvs communication pattern

The core communication logic lives in `kvs`:

```scala
def kvs(
    reqC: Request @@ "client",
    stateS: Ref[IO, State] @@ "server"
): Choreo[IO, Response @@ "client"] =
  for
    reqS <- client.send(reqC).to(server)
    resS <- server.locally(handleRequest(reqS.!, stateS.!))
    resC <- server.send(resS).to(client)
  yield resC
```

This is the choreographic essence of the protocol:

1. `client.send(reqC).to(server)` -- The client sends its request to the server.
   The result `reqS` has type `Request @@ "server"`, reflecting that the value now
   resides at the server.

2. `server.locally(handleRequest(reqS.!, stateS.!))` -- The server processes the
   request against its local state. Both `reqS` and `stateS` are unwrapped with `.!`
   because the server owns them.

3. `server.send(resS).to(client)` -- The server sends the response back. The
   returned `resC` has type `Response @@ "client"`.

The `handleRequest` function is a regular function with no choreographic awareness:

```scala
def handleRequest(
    req: Request,
    state: Ref[IO, State]
): IO[Response] =
  req match
    case Request.Get(key) =>
      state.get.map(_.get(key))
    case Request.Put(key, value) =>
      state.update(_.updated(key, value)).as(Some(value))
```

## Running with Backend.local

The `main` function ties everything together by projecting the choreography onto
each location and running both endpoints concurrently:

```scala
def main: IO[Unit] =
  for
    backend   <- Backend.local(List(client, server))
    clientTask = choreo.project(backend, client)
    serverTask = choreo.project(backend, server)
    _         <- (clientTask, serverTask).parTupled
  yield ()
```

`Backend.local` creates a `LocalBackend` with in-memory queues connecting the two
locations. The `.project(backend, loc)` extension method on `Choreo` performs
endpoint projection (EPP) -- transforming the global choreography into a location-specific
`Network` program -- and then interprets it using the backend.

Both tasks are run in parallel with `parTupled` from Cats. When the client reads
a request and sends it, the message appears in the server's inbox queue; when the
server sends a response, it appears in the client's inbox queue.
