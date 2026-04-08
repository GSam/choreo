package choreo
package examples
package distributedbookseller

import cats.effect.{IO, IOApp}
import cats.effect.IO.asyncForIO

import java.net.InetSocketAddress

import choreo.backend.TcpBackend

val alice: "alice" = "alice"
val bob: "bob"     = "bob"

val addresses = Map(
  alice -> new InetSocketAddress("localhost", 9001),
  bob   -> new InetSocketAddress("localhost", 9002)
)

case class Book(title: String, price: Double)

val catalog = List(
  Book("Functional Programming in Scala", 121.0),
  Book("Scala with Cats", 42.0)
)

def protocol: Choreo[IO, Option[String @@ "alice"]] =
  for
    titleA <- alice.locally:
                IO.print("[alice] Enter book title: ") *> IO.readLine

    titleB <- alice.send(titleA).to(bob)

    priceB <- bob.locally:
                val book = catalog.find(_.title == titleB.!)
                IO.println(s"[bob] Looking up '${titleB.!}' ...") *>
                  IO.pure(book.map(_.price).getOrElse(-1.0))

    priceA <- bob.send(priceB).to(alice)

    decision <- alice.locally:
                  if priceA.! < 0 then
                    IO.println("[alice] Book not found.") *> IO.pure(false)
                  else
                    IO.println(s"[alice] Price is ${priceA.!}") *>
                      IO.print("[alice] Buy? [y/n] ") *>
                      IO.readLine.map(_ == "y")

    result <- alice.select(decision)(
                true -> (for
                  dateB <- bob.locally:
                             IO.println("[bob] Shipping book!") *> IO.pure("2026-12-24")
                  dateA <- bob.send(dateB).to(alice)
                  _ <- alice.locally:
                         IO.println(s"[alice] Book will arrive on ${dateA.!}")
                yield Some(dateA)),

                false -> (for
                  _ <- bob.locally(IO.println("[bob] No sale."))
                  _ <- alice.locally(IO.println("[alice] Maybe next time."))
                yield None)
              )
  yield result

def runAlice: IO[Unit] =
  TcpBackend.distributed[IO](addresses, alice).use { backend =>
    IO.println(s"[alice] Listening on ${addresses(alice)} ...") *>
      protocol.project(backend, alice).void
  }

def runBob: IO[Unit] =
  TcpBackend.distributed[IO](addresses, bob).use { backend =>
    IO.println(s"[bob] Listening on ${addresses(bob)} ...") *>
      protocol.project(backend, bob).void
  }

object aliceMain extends IOApp.Simple:
  def run: IO[Unit] = runAlice

object bobMain extends IOApp.Simple:
  def run: IO[Unit] = runBob
