package choreo
package backend

import cats.effect.Deferred
import cats.effect.std.Queue
import cats.effect.kernel.Concurrent
import cats.syntax.all.*

import choreo.utils.toFunctionK

class LocalBackend[M[_]](inboxes: Map[Channel, Queue[M, Any]], val locs: Seq[Loc]):

  def runNetwork[A](at: Loc)(
      network: Network[M, A]
  )(using M: Concurrent[M]): M[A] =
    network.foldMap(run(at, inboxes).toFunctionK)

  private[choreo] def run(
      at: Loc,
      inboxes: Map[Channel, Queue[M, Any]]
  )(using M: Concurrent[M]): [A] => NetworkSig[M, A] => M[A] = [A] =>
    (na: NetworkSig[M, A]) =>
      na match
        case NetworkSig.Run(ma) =>
          ma

        case NetworkSig.Send(a, to) =>
          val inbox = inboxes((from = at, to = to))
          inbox.offer(a)

        case NetworkSig.Recv(from) =>
          val inbox = inboxes((from = from, to = at))
          inbox.take.map(_.asInstanceOf[A])

        case NetworkSig.AsyncRecv(from) =>
          val inbox = inboxes((from = from, to = at))
          (for
            d <- Deferred[M, Any]
            _ <- M.start(inbox.take.flatMap(v => d.complete(v).void))
          yield d.get).asInstanceOf[M[A]]

        case NetworkSig.Broadcast(a) =>
          locs
            .filter(_ != at)
            .traverse_ { to =>
              run(at, inboxes)(NetworkSig.Send(a, to))
            }

        case NetworkSig.Par(left, right) =>
          val leftM  = left.foldMap(run(at, inboxes).toFunctionK)
          val rightM = right.foldMap(run(at, inboxes).toFunctionK)
          M.both(leftM, rightM).asInstanceOf[M[A]]

object LocalBackend:
  def apply[M[_]: Concurrent](locs: Seq[Loc]): M[LocalBackend[M]] =
    for inboxes <- makeInboxes(locs)
    yield new LocalBackend(inboxes, locs)

  def makeInboxes[M[_]: Concurrent](
      locs: Seq[Loc]
  ): M[Map[Channel, Queue[M, Any]]] =
    val channels = for { s <- locs; r <- locs; if s != r } yield (from = s, to = r)
    for queues <- channels.traverse(_ => Queue.unbounded[M, Any])
    yield channels.zip(queues).toMap

  given localBackend[M[_]: Concurrent]: Backend[LocalBackend[M], M] with
    extension (b: LocalBackend[M])
      def runNetwork[A](at: Loc)(network: Network[M, A]): M[A] =
        b.runNetwork(at)(network)
      def locs: Set[Loc]                                       = b.locs.toSet
