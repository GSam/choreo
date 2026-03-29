package choreo
package backend

import cats.Monad
import cats.free.Free
import cats.arrow.FunctionK
import cats.effect.std.Queue
import cats.effect.kernel.Concurrent
import cats.syntax.all.*

import choreo.utils.toFunctionK

trait Backend[B, M[_]]:
  extension (backend: B) def runNetwork[A](at: Loc)(network: Network[M, A]): M[A]
  extension (backend: B) def locs: Set[Loc]

object Backend:
  def local[M[_]: Concurrent](
      locs: List[Loc]
  ): M[LocalBackend[M]] =
    for inboxes <- LocalBackend.makeInboxes(locs)
    yield LocalBackend(inboxes, locs)

type Channel = (from: Loc, to: Loc)
