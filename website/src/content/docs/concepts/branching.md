---
title: Branching
sidebar:
  order: 3
---

Distributed programs often need to make decisions that affect multiple participants. Choreo provides two branching primitives: `cond` for conditional execution and `select` for label-based multi-way branching. Both handle the **knowledge-of-choice** problem automatically.

## The Knowledge-of-Choice Problem

In a choreography, when one location makes a decision, other locations that are affected by the outcome need to learn which branch was taken. Without this information, they cannot know which operations to execute. This is the knowledge-of-choice problem.

Choreo solves this by having the deciding location broadcast its decision to all other involved participants as part of the branching primitives.

## Conditional Execution: `cond`

The `cond` method lets a location branch on a value it owns:

```scala
extension [L <: Loc](l: L)
  def cond[M[_], A, B](a: A @@ L)(f: A => Choreo[M, B]): Choreo[M, B]
```

The location `l` unwraps its value `a`, applies `f` to get the branch choreography, and broadcasts the decision value to all other involved participants. Each participant then projects the chosen branch.

### Example

```scala
val alice: "alice" = "alice"
val bob: "bob" = "bob"

def greetIfPositive(n: Int @@ "alice"): Choreo[IO, Unit] =
  alice.cond(n): value =>
    if value > 0 then
      for
        msgA <- alice.locally(IO.pure("Positive!"))
        msgB <- alice.send(msgA).to(bob)
        _    <- bob.locally(IO.println(s"Bob heard: ${msgB.!}"))
      yield ()
    else
      Choreo.pure(())
```

At the DSL level, this corresponds to `ChoreoSig.Cond(l, a, f)`.

### How Broadcast Works

During endpoint projection, when location `l` evaluates a `Cond`:

- **At `l`**: the value is unwrapped, the branch choreography is computed, and the decision value is sent to every other involved participant (wrapped in `Some`). Participants not involved in any branch receive `None`.
- **At other involved locations**: the decision value is received from `l`, and the corresponding branch is projected.
- **At uninvolved locations**: `None` is received, and the operation is skipped.

## Label-Based Branching: `select`

The `select` method provides multi-way branching using labeled branches:

```scala
extension [L <: Loc](l: L)
  def select[M[_], A, Label](
    label: Label @@ L
  )(branches: (Label, Choreo[M, A])*): Choreo[M, A]
```

The location `l` selects a branch by its label and broadcasts the label to all involved participants. Each participant then executes the selected branch.

### Example

The bookseller example uses `select` to let the buyer decide whether to purchase a book:

```scala
val buyer: "buyer" = "buyer"
val seller: "seller" = "seller"

// buyer.select chooses a branch based on the decision label
buyer.select(decision)(
  true -> (for
    deliveryDateS <- seller.locally(IO.pure(Date(2024, 12, 24)))
    deliveryDateB <- seller.send(deliveryDateS).to(buyer)
    _ <- buyer.locally:
           IO.println(s"Book will be delivered on ${deliveryDateB.!}")
  yield Some(deliveryDateB)),

  false -> (buyer.locally(IO.println("Ok, bye!")) *> Choreo.pure(None))
)
```

At the DSL level, this corresponds to `ChoreoSig.Select(l, label, branches)`.

### How Select Differs from Cond

| Aspect | `cond` | `select` |
|--------|--------|----------|
| Decision mechanism | Arbitrary function `A => Choreo[M, B]` | Label lookup from a fixed set of branches |
| Branch determination | The function `f` computes the branch dynamically | Branches are enumerated upfront as a map |
| Broadcast content | The decision value itself (wrapped in `Option`) | The label key |
| Use case | General conditional logic | Finite, well-defined choices |

Both primitives ensure that all affected participants learn which branch was chosen, maintaining the correctness of the distributed protocol.
