package choreo
package examples
package auction

import cats.effect.{IO, IOApp}
import cats.effect.IO.asyncForIO
import cats.syntax.all.*

import java.net.InetSocketAddress

import choreo.backend.TcpBackend

val auctioneer: "auctioneer" = "auctioneer"
val bidderA: "bidderA"       = "bidderA"
val bidderB: "bidderB"       = "bidderB"

case class Bid(bidder: String, amount: Double)

val addresses = Map(
  auctioneer -> new InetSocketAddress("localhost", 9001),
  bidderA    -> new InetSocketAddress("localhost", 9002),
  bidderB    -> new InetSocketAddress("localhost", 9003)
)

def protocol: Choreo[IO, Unit] =
  for
    // Auctioneer announces the item
    itemA <- auctioneer.locally:
               IO.println("[auctioneer] Announcing item: Vintage Lamp") *> IO.pure("Vintage Lamp")

    itemBA <- auctioneer.send(itemA).to(bidderA)
    itemBB <- auctioneer.send(itemA).to(bidderB)

    // Both bidders submit bids in parallel via asyncSend
    (bidAHandle, bidBHandle) <- {
      val left: Choreo[IO, IO[Bid] @@ "auctioneer"] =
        for
          bid <- bidderA.locally:
                   IO.println(s"[bidderA] Bidding on ${itemBA.!}") *>
                     IO.print("[bidderA] Your bid: ") *> IO.readLine.map(s => Bid("bidderA", s.toDouble))
          handle <- bidderA.asyncSend(bid).to(auctioneer)
        yield handle

      val right: Choreo[IO, IO[Bid] @@ "auctioneer"] =
        for
          bid <- bidderB.locally:
                   IO.println(s"[bidderB] Bidding on ${itemBB.!}") *>
                     IO.print("[bidderB] Your bid: ") *> IO.readLine.map(s => Bid("bidderB", s.toDouble))
          handle <- bidderB.asyncSend(bid).to(auctioneer)
        yield handle

      left |*| right
    }

    // Auctioneer collects both bids and picks the winner
    winner <- auctioneer.locally:
                for
                  a <- bidAHandle.!
                  b <- bidBHandle.!
                  _ <- IO.println(s"[auctioneer] Bids: ${a.bidder}=${a.amount}, ${b.bidder}=${b.amount}")
                yield if a.amount >= b.amount then a.bidder else b.bidder

    // Announce result via select
    _ <- auctioneer.select(winner)(
           "bidderA" -> (for
             _ <- bidderA.locally(IO.println("[bidderA] I won the auction!"))
             _ <- bidderB.locally(IO.println("[bidderB] I lost the auction."))
             _ <- auctioneer.locally(IO.println("[auctioneer] Winner: bidderA"))
           yield ()),
           "bidderB" -> (for
             _ <- bidderA.locally(IO.println("[bidderA] I lost the auction."))
             _ <- bidderB.locally(IO.println("[bidderB] I won the auction!"))
             _ <- auctioneer.locally(IO.println("[auctioneer] Winner: bidderB"))
           yield ())
         )
  yield ()

def runAuctioneer: IO[Unit] =
  TcpBackend.distributed[IO](addresses, auctioneer).use { backend =>
    IO.println(s"[auctioneer] Listening on ${addresses(auctioneer)} ...") *>
      protocol.project(backend, auctioneer)
  }

def runBidderA: IO[Unit] =
  TcpBackend.distributed[IO](addresses, bidderA).use { backend =>
    IO.println(s"[bidderA] Listening on ${addresses(bidderA)} ...") *>
      protocol.project(backend, bidderA)
  }

def runBidderB: IO[Unit] =
  TcpBackend.distributed[IO](addresses, bidderB).use { backend =>
    IO.println(s"[bidderB] Listening on ${addresses(bidderB)} ...") *>
      protocol.project(backend, bidderB)
  }

object auctioneerMain extends IOApp.Simple:
  def run: IO[Unit] = runAuctioneer

object bidderAMain extends IOApp.Simple:
  def run: IO[Unit] = runBidderA

object bidderBMain extends IOApp.Simple:
  def run: IO[Unit] = runBidderB
