---
title: Bookseller
sidebar:
  order: 2
---

The bookseller example implements a two-party purchase protocol adapted from the
choreographic programming literature. A buyer queries a seller for a book price,
decides whether to purchase, and the protocol branches accordingly. This example
demonstrates label-based branching with `select` and knowledge-of-choice
propagation.

Source: `examples/src/Bookseller.scala`

## Locations and types

```scala
val buyer: "buyer"   = "buyer"
val seller: "seller" = "seller"

case class Book(title: String, price: Double)

case class Date(year: Int, month: Int, day: Int):
  override def toString(): String = s"$year-$month-$day"

val books = List(
  Book("Functional Programming in Scala", 121.0),
  Book("Scala with Cats", 42.0)
)
```

The seller maintains a catalog of books. `Date` is a simple value type for delivery dates.

## The protocol choreography

The entire protocol is expressed as a single `Choreo[IO, Option[Date @@ "buyer"]]`:

```scala
def protocol: Choreo[IO, Option[Date @@ "buyer"]] =
  for
    titleB <- buyer.locally:
                IO.print("Enter book title: ") *> IO.readLine

    titleS <- buyer.send(titleB).to(seller)

    priceS <- seller.locally:
                for
                  book  <- IO.pure(books.find(_.title == titleS.!))
                  price <- book match
                             case Some(b) => IO.pure(b.price)
                             case None    => IO.raiseError(new Exception("Book not found"))
                yield price

    priceB <- seller.send(priceS).to(buyer)

    decision <- buyer.locally:
                  IO.println(s"Price of ${titleB.!} is ${priceB.!}") *>
                    IO.print("Do you want to buy it? [y/n] ") *>
                    IO.readLine.map(_ == "y")

    deliveryDate <- buyer.select(decision)(
                      true -> (for
                        deliveryDateS <- seller.locally(IO.pure(Date(2024, 12, 24)))
                        deliveryDateB <- seller.send(deliveryDateS).to(buyer)
                        _ <- buyer.locally:
                               IO.println(s"Book will be delivered on ${deliveryDateB.!}")
                      yield Some(deliveryDateB)),

                      false -> (buyer.locally(IO.println("Ok, bye!")) *> Choreo.pure(None))
                    )
  yield deliveryDate
```

The protocol proceeds in several phases:

### Phase 1: Title lookup

The buyer reads a book title from stdin and sends it to the seller. The seller
looks up the book in its catalog and computes the price. If the book is not found,
the seller raises an error.

### Phase 2: Price negotiation

The seller sends the price back to the buyer. The buyer displays the price and
asks the user whether to proceed with the purchase. The result `decision` has type
`Boolean @@ "buyer"` -- a boolean known only to the buyer.

### Phase 3: Branching with `select`

This is the most interesting part. The buyer calls `select` to branch the
choreography based on its local decision:

```scala
buyer.select(decision)(
  true  -> deliveryBranch,
  false -> goodbyeBranch
)
```

## How `select` handles knowledge-of-choice

In choreographic programming, when one location makes a decision that affects the
control flow of the entire protocol, all other involved locations must learn which
branch was chosen. This is the *knowledge-of-choice* problem.

The `select` primitive solves this automatically. During endpoint projection:

- **At the buyer** (the selecting location): the label value (`true` or `false`) is
  unwrapped and broadcast to all other involved locations, then the corresponding
  branch is projected and executed.

- **At the seller** (a non-selecting location): the projected code receives the
  label from the buyer, looks up the corresponding branch, and projects and
  executes it.

This means the seller learns which branch the buyer chose without the programmer
having to manually coordinate the communication. The global protocol describes the
branching once, and EPP ensures both sides agree.

### The `true` branch (purchase)

```scala
true -> (for
  deliveryDateS <- seller.locally(IO.pure(Date(2024, 12, 24)))
  deliveryDateB <- seller.send(deliveryDateS).to(buyer)
  _ <- buyer.locally:
         IO.println(s"Book will be delivered on ${deliveryDateB.!}")
yield Some(deliveryDateB))
```

The seller computes a delivery date, sends it to the buyer, and the buyer prints
a confirmation. The branch returns `Some(deliveryDateB)`.

### The `false` branch (no purchase)

```scala
false -> (buyer.locally(IO.println("Ok, bye!")) *> Choreo.pure(None))
```

The buyer prints a farewell message and the branch returns `None`. The seller does
nothing in this branch.

## Running with Backend.local

```scala
def main: IO[Unit] =
  for
    backend <- Backend.local(List(buyer, seller))

    sellerIO = protocol.project(backend, seller)
    buyerIO  = protocol.project(backend, buyer)

    _ <- (sellerIO, buyerIO).parTupled
  yield ()
```

As with the KV example, `Backend.local` creates an in-memory backend and
`.project` transforms the global choreography into per-location IO tasks that
run concurrently.
