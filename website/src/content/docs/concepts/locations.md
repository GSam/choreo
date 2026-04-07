---
title: Locations
sidebar:
  order: 1
---

Locations are the foundation of Choreo's type-safe ownership model. Every value in a choreography is tagged with the location that owns it, and the type system ensures that only the owning location can access the underlying data.

## Defining Locations

A location is simply a `String` singleton type:

```scala
type Loc = String

val alice: "alice" = "alice"
val bob: "bob" = "bob"
```

The singleton type annotation (e.g., `"alice"`) is essential. It allows the compiler to distinguish between different locations at the type level.

## Located Values: `At[A, L]` and `@@`

The `At[A, L]` enum represents a value of type `A` that is owned by location `L`:

```scala
enum At[A, L <: Loc]:
  case Wrap(a: A) extends At[A, L]
  case Empty()    extends At[A, L]
```

- `Wrap(a)` holds an actual value -- used at the owning location.
- `Empty()` represents the absence of a value -- used at all other locations.

The `@@` type alias provides a more readable syntax:

```scala
type @@[A, L <: Loc] = At[A, L]

// These two types are equivalent:
//   At[String, "alice"]
//   String @@ "alice"
```

You can wrap a plain value with `.at`:

```scala
val msg: String @@ "alice" = "hello".at(alice)
```

## Unwrapping: The `!` Operator

Only the owning location should be able to read the actual value inside an `At`. This is enforced through the `Unwrap[L]` context function:

```scala
type Unwrap[L <: Loc] = [A] => A @@ L => A
```

The `!` extension method uses `Unwrap` to extract the value:

```scala
extension [A, L <: Loc](a: A @@ L)
  def !(using U: Unwrap[L]): A = U(a)
```

An `Unwrap[L]` instance is only available inside a `locally` block for location `L`. This means you can only call `!` on values owned by the current location:

```scala
val alice: "alice" = "alice"

// Inside alice.locally, we can unwrap alice's values
alice.locally:
  val msg: String @@ "alice" = ...
  IO.println(msg.!)  // OK: we are at alice, so we can unwrap

// bob cannot unwrap alice's values
bob.locally:
  val msg: String @@ "alice" = ...
  // msg.!  -- this would not compile: no Unwrap["alice"] in scope
```

This mechanism is the key safety property of choreographic programming: it prevents locations from accessing data they do not own, catching these errors at compile time rather than at runtime.

## Summary

| Concept | Type | Purpose |
|---------|------|---------|
| Location | `Loc` (a `String` singleton) | Identifies a participant |
| Located value | `A @@ L` / `At[A, L]` | A value owned by location `L` |
| Wrap | `At.Wrap(a)` | Holds an actual value at the owning location |
| Empty | `At.Empty()` | Placeholder at non-owning locations |
| Unwrap | `Unwrap[L]` | Context function that permits reading a value |
| `!` operator | `(a: A @@ L).!` | Extracts the value (requires `Unwrap[L]`) |
| `.at` wrapper | `value.at(loc)` | Wraps a plain value as a located value |
