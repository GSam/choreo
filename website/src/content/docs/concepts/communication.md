---
title: Communication
sidebar:
  order: 2
---

Choreo provides two fundamental operations for building choreographies: **local computation** at a single location, and **point-to-point communication** between two locations.

## Local Computation: `locally`

The `locally` method runs an effectful computation at a specific location:

```scala
val alice: "alice" = "alice"

val computation: Choreo[IO, String @@ "alice"] =
  alice.locally(IO.pure("hello"))
```

The signature is:

```scala
extension [L <: Loc](l: L)
  def locally[M[_], A](m: Unwrap[l.type] ?=> M[A]): Choreo[M, A @@ l.type]
```

Key points:

- The body `m` receives an implicit `Unwrap[l.type]`, so you can use `!` to unwrap values owned by `l` inside the block.
- The result is wrapped as `A @@ l.type`, tagging it with the owning location.
- Only the specified location actually executes the computation. All other locations receive `At.Empty()` after endpoint projection.

At the choreography DSL level, `locally` corresponds to `ChoreoSig.Local(l, m)`.

### Example

```scala
val alice: "alice" = "alice"

// Alice reads a line from the console
val readLine: Choreo[IO, String @@ "alice"] =
  alice.locally:
    IO.print("Enter message: ") *> IO.readLine
```

## Point-to-Point Communication: `send` / `to`

To send a value from one location to another, use the two-step `send(...).to(...)` pattern:

```scala
val alice: "alice" = "alice"
val bob: "bob" = "bob"

val transfer: Choreo[IO, String @@ "bob"] =
  for
    msgA <- alice.locally(IO.pure("hello"))
    msgB <- alice.send(msgA).to(bob)
  yield msgB
```

The `send` method creates an intermediate `Sendable` value:

```scala
extension [L <: Loc](l: L)
  def send[A](a: A @@ L): Sendable[A, L]
```

Then `to` completes the communication:

```scala
extension [A, Src <: Loc](s: Sendable[A, Src])
  def to[M[_], Dst <: Loc](dst: Dst): Choreo[M, A @@ dst.type]
```

The result type changes from `A @@ Src` to `A @@ Dst`, reflecting that ownership has transferred. At the DSL level, this corresponds to `ChoreoSig.Comm(src, a, dst)`.

## Composing with For-Comprehensions

Since `Choreo[M, A]` is a free monad, choreographies compose naturally using Scala's for-comprehensions. This lets you write sequential multi-step protocols in a readable style:

```scala
val alice: "alice" = "alice"
val bob: "bob" = "bob"

def protocol: Choreo[IO, Unit] =
  for
    // Alice creates a request
    reqA <- alice.locally(IO.pure("What is the answer?"))

    // Alice sends the request to Bob
    reqB <- alice.send(reqA).to(bob)

    // Bob processes the request and creates a response
    resB <- bob.locally:
              IO.pure(s"The answer to '${reqB.!}' is 42")

    // Bob sends the response back to Alice
    resA <- bob.send(resB).to(alice)

    // Alice prints the response
    _ <- alice.locally:
           IO.println(s"Got response: ${resA.!}")
  yield ()
```

Each step in the for-comprehension is a choreographic operation that specifies which locations are involved and how data flows between them.
