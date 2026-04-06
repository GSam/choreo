{%
laika.title = "Endpoint Projection"
%}

# Endpoint Projection

Endpoint projection (EPP) is the core compilation step in choreographic programming. It transforms a global choreography -- a single description of the entire distributed protocol -- into a network program for each individual participant.

## The `Endpoint.project` Function

```scala
object Endpoint:
  def project[M[_], A](
    c: Choreo[M, A],
    at: Loc,
    locs: Set[Loc]
  ): Network[M, A]
```

Given a choreography `c`, a target location `at`, and the set of all locations `locs`, this function produces a `Network[M, A]` -- the sequence of network operations (send, receive, run, broadcast) that `at` must perform.

In practice, you typically use the convenience method on `Choreo`:

```scala
val program: M[A] = choreography.project(backend, location)
```

This calls `Endpoint.project` internally and then interprets the resulting network program using the backend.

## Projection Rules

Each `ChoreoSig` constructor is projected differently depending on whether the target location `at` is the source, the destination, or an uninvolved party.

### Local

`ChoreoSig.Local(loc, m)` -- run a local computation at `loc`.

| Role | Projection |
|------|------------|
| `at == loc` | `Network.Run(m(unwrap))` -- execute the computation, wrap the result |
| `at != loc` | `Network.pure(At.Empty())` -- produce an empty located value |

### Comm

`ChoreoSig.Comm(src, a, dst)` -- send a value from `src` to `dst`.

| Role | Projection |
|------|------------|
| `at == src` | `Network.Send(unwrap(a), dst)` -- unwrap and send the value |
| `at == dst` | `Network.Recv(src)` -- receive the value and wrap it |
| otherwise | `Network.pure(At.Empty())` -- no-op |

### Cond

`ChoreoSig.Cond(loc, a, f)` -- conditional branching at `loc`.

| Role | Projection |
|------|------------|
| `at == loc` | Unwrap the decision value, compute the branch via `f`, send the decision to all involved locations (as `Some(value)`) and `None` to uninvolved locations, then project the branch |
| `at != loc` (involved) | Receive the decision from `loc`; if `Some(value)`, project `f(value)`; if `None`, skip |
| `at != loc` (uninvolved) | Same receive; gets `None` and produces a placeholder |

### Select

`ChoreoSig.Select(loc, label, branches)` -- label-based multi-way branching at `loc`.

| Role | Projection |
|------|------------|
| `at == loc` | Unwrap the label, send it to all locations involved in any branch, then project the selected branch |
| `at != loc` (involved) | Receive the label from `loc`, then project the corresponding branch |
| otherwise | Produce a placeholder value |

### Par

`ChoreoSig.Par(left, right)` -- parallel composition.

| Role | Projection |
|------|------------|
| `at` is in left branch | Project `left` for `at` |
| `at` is in right branch | Project `right` for `at` |
| `at` is in both | Project both, combine with `Network.par` |
| `at` is in neither | Produce placeholder values for both |

The sets of involved locations are determined by `collectLocations`, which statically analyzes each branch.

## From Network to Effect

After projection, the `Network[M, A]` free monad is interpreted by a backend:

```scala
trait Backend[B, M[_]]:
  extension (backend: B)
    def runNetwork[A](at: Loc)(network: Network[M, A]): M[A]
    def locs: Set[Loc]
```

The backend maps each `NetworkSig` operation to concrete effects:

| NetworkSig | Effect |
|------------|--------|
| `Run(ma)` | Execute the local effect `ma` |
| `Send(a, to)` | Enqueue or transmit `a` to the destination |
| `Recv(from)` | Dequeue or receive a value from the source |
| `Broadcast(a)` | Send `a` to all other locations |
| `Par(left, right)` | Run both network programs concurrently |

## Correctness

The projection is designed so that the distributed execution of all projected network programs is equivalent to running the choreography locally (via `runLocal`). This property -- known as **deadlock freedom by construction** -- is the central benefit of choreographic programming: if the choreography type-checks, the distributed system is guaranteed to be free of communication deadlocks.
