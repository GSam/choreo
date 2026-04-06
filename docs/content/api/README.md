{%
laika.title = "API Reference"
%}

# API Reference

Quick-reference tables for the Choreo DSL, core types, and backend factories.

## DSL operations

These extension methods are available on location values (singleton `String` types)
and on `Choreo` values.

| Operation | Signature | Description |
|-----------|-----------|-------------|
| `locally` | `l.locally(m: Unwrap[L] ?=> M[A]): Choreo[M, A @@ L]` | Execute a local effect `m` at location `l`. The `Unwrap[L]` context lets you unwrap values owned by `l` inside the block. |
| `send` / `to` | `l.send(a: A @@ L): Sendable[A, L]` then `.to(dst): Choreo[M, A @@ Dst]` | Send a value from `l` to `dst`. Returns the value located at the destination. |
| `cond` | `l.cond(a: A @@ L)(f: A => Choreo[M, B]): Choreo[M, B]` | Branch the choreography based on a value at `l`. The deciding location broadcasts the value to involved participants. |
| `select` | `l.select(label: Label @@ L)(branches: (Label, Choreo[M, A])*): Choreo[M, A]` | Branch using a label known at `l`. The label is broadcast so all involved locations follow the correct branch. |
| `\|*\|` | `left \|*\| right: Choreo[M, (A, B)]` | Parallel composition. Run two choreographies concurrently. |
| `Choreo.par` | `Choreo.par(left, right): Choreo[M, (A, B)]` | Parallel composition (function form). |
| `Choreo.pure` | `Choreo.pure(a: A): Choreo[M, A]` | Lift a pure value into a choreography. |
| `.project` | `choreo.project(backend, at): M[A]` | Project the choreography to location `at` and run it on the given backend. |
| `.runLocal` | `choreo.runLocal: M[A]` | Run the choreography locally without a backend (no real communication). Useful for testing logic. |

## Core types

| Type | Definition | Description |
|------|------------|-------------|
| `Loc` | `type Loc = String` | A location identifier. |
| `A @@ L` | `At[A, L]` (enum: `Wrap(a)` \| `Empty()`) | A value of type `A` located at `L`. Only the owning location holds `Wrap`; others hold `Empty`. |
| `Unwrap[L]` | `[A] => A @@ L => A` | A polymorphic function that extracts a value from `A @@ L`. Provided as a context function during projection. |
| `Choreo[M, A]` | `Free[ChoreoSig[M, _], A]` | A choreography: a global protocol description parameterized by effect type `M`. |
| `Network[M, A]` | `Free[NetworkSig[M, _], A]` | A network program: a per-location program produced by endpoint projection. |
| `Channel` | `(from: Loc, to: Loc)` | A directed communication path between two locations. |
| `Sendable[A, L]` | Opaque: `(src: L, value: A @@ L)` | Intermediate type returned by `.send`, consumed by `.to`. |
| `WireCodec` | Trait: `encode(Any): Array[Byte]`, `decode(Array[Byte]): Any` | Serialization codec for the TCP backend. |

## Choreography signature (`ChoreoSig`)

The free monad functor underlying `Choreo`:

| Variant | Type | Description |
|---------|------|-------------|
| `Local[M, A, L]` | `ChoreoSig[M, A @@ L]` | Local computation at location `L`. |
| `Comm[M, A, L0, L1]` | `ChoreoSig[M, A @@ L1]` | Communication from `L0` to `L1`. |
| `Cond[M, A, B, L]` | `ChoreoSig[M, B]` | Conditional branching based on a value at `L`. |
| `Select[M, A, L, Label]` | `ChoreoSig[M, A]` | Label-based branching at `L`. |
| `Par[M, A, B]` | `ChoreoSig[M, (A, B)]` | Parallel composition of two choreographies. |

## Network signature (`NetworkSig`)

The free monad functor underlying `Network`:

| Variant | Type | Description |
|---------|------|-------------|
| `Run(ma)` | `NetworkSig[M, A]` | Execute a local effect. |
| `Send(a, to)` | `NetworkSig[M, Unit]` | Send a value to a location. |
| `Recv(from)` | `NetworkSig[M, A]` | Receive a value from a location. |
| `Broadcast(a)` | `NetworkSig[M, Unit]` | Send a value to all other locations. |
| `Par(left, right)` | `NetworkSig[M, (A, B)]` | Run two network programs in parallel. |

## Backend factories

| Factory | Signature | Description |
|---------|-----------|-------------|
| `Backend.local` | `(locs: Seq[Loc]): M[LocalBackend[M]]` | Create an in-memory backend. Requires `Concurrent[M]`. |
| `TcpBackend.local` | `(locs: List[Loc], codec: WireCodec): Resource[M, TcpBackend[M]]` | Create a TCP backend. Requires `Async[M]`. Returns a `Resource`. |

## Backend type class

```scala
trait Backend[B, M[_]]:
  extension (backend: B)
    def runNetwork[A](at: Loc)(network: Network[M, A]): M[A]
    def locs: Set[Loc]
```

Given instances are provided for both `LocalBackend` and `TcpBackend`:

- `LocalBackend.localBackend[M[_]: Concurrent]: Backend[LocalBackend[M], M]`
- `TcpBackend.tcpBackend[M[_]: Async]: Backend[TcpBackend[M], M]`
