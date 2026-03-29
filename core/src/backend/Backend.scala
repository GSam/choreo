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
  val local = LocalBackend
  val tcp   = TcpBackend

type Channel = (from: Loc, to: Loc)
