---
title: Parallel Composition
sidebar:
  order: 4
---

Choreo supports running independent parts of a choreography concurrently using parallel composition. This allows you to express protocols where multiple interactions happen simultaneously.

## The `|*|` Operator

The `|*|` infix operator composes two choreographies in parallel:

```scala
extension [M[_], A](self: Choreo[M, A])
  infix def |*|[B](other: Choreo[M, B]): Choreo[M, (A, B)]
```

The result is a tuple of both results. At the DSL level, this corresponds to `ChoreoSig.Par(left, right)`.

You can also use the `Choreo.par` method directly:

```scala
object Choreo:
  def par[M[_], A, B](
    left: Choreo[M, A],
    right: Choreo[M, B]
  ): Choreo[M, (A, B)]
```

### Example

Suppose Alice needs to fetch data from both Bob and Carol independently:

```scala
val alice: "alice" = "alice"
val bob: "bob" = "bob"
val carol: "carol" = "carol"

def fetchFromBob: Choreo[IO, String @@ "alice"] =
  for
    dataB <- bob.locally(IO.pure("Bob's data"))
    dataA <- bob.send(dataB).to(alice)
  yield dataA

def fetchFromCarol: Choreo[IO, Int @@ "alice"] =
  for
    dataC <- carol.locally(IO.pure(42))
    dataA <- carol.send(dataC).to(alice)
  yield dataA

def fetchBoth: Choreo[IO, (String @@ "alice", Int @@ "alice")] =
  fetchFromBob |*| fetchFromCarol
```

The two sub-choreographies execute concurrently because they involve independent communication channels.

## How EPP Handles Parallel Composition

During endpoint projection, the `Par` case uses `collectLocations` to determine which locations are involved in each branch:

- If the current location is involved in the **left** branch, its network program for that branch is projected. Otherwise, a no-op placeholder is used.
- The same logic applies to the **right** branch.
- Both projected network programs are combined using `Network.par`, which the backend executes concurrently (e.g., using `Concurrent[M].both` in the local backend).

This means a location that only participates in one branch does not block on the other. The projection is precise: only locations that actually appear in a branch are included in its execution.

### Location Collection

The `collectLocations` function statically analyzes a choreography to determine the set of locations involved. It traverses the free monad structure and collects locations from each operation:

- `Local(loc, _)` contributes `loc`
- `Comm(src, _, dst)` contributes `src` and `dst`
- `Cond(loc, _, f)` and `Select(loc, _, branches)` contribute `loc` plus all locations in the sub-choreographies
- `Par(left, right)` contributes the union of both branches

This analysis ensures that parallel composition is projected correctly, even when branches have overlapping participants.
