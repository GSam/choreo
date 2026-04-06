package choreo

import scala.annotation.targetName

import cats.Monad
import cats.free.Free
import cats.effect.IO
import cats.effect.kernel.Concurrent
import cats.syntax.all.*
import cats.arrow.FunctionK

import choreo.backend.Backend
import choreo.utils.toFunctionK

type Choreo[M[_], A] = Free[[X] =>> ChoreoSig[M, X], A]

extension [M[_], A](c: Choreo[M, A])
  def project[B](backend: B, at: Loc)(using B: Backend[B, M]): M[A] =
    backend.runNetwork(at)(Endpoint.project(c, at, backend.locs))

object Choreo:
  def pure[M[_], A](a: A): Choreo[M, A] = Free.pure(a)

  def par[M[_], A, B](left: Choreo[M, A], right: Choreo[M, B]): Choreo[M, (A, B)] =
    Free.liftF(ChoreoSig.Par(left, right))

extension [M[_], A](self: Choreo[M, A])
  infix def |*|[B](other: Choreo[M, B]): Choreo[M, (A, B)] =
    Choreo.par(self, other)

enum ChoreoSig[M[_], A]:
  case Local[M[_], A, L <: Loc](l: L, m: Unwrap[L] => M[A]) extends ChoreoSig[M, A @@ L]

  case Comm[M[_], A, L0 <: Loc, L1 <: Loc](l0: L0, a: A @@ L0, l1: L1) extends ChoreoSig[M, A @@ L1]

  case AsyncComm[M[_], A, L0 <: Loc, L1 <: Loc](l0: L0, a: A @@ L0, l1: L1)
      extends ChoreoSig[M, M[A] @@ L1]

  case Cond[M[_], A, B, L <: Loc](l: L, a: A @@ L, f: A => Choreo[M, B]) extends ChoreoSig[M, B]

  case Select[M[_], A, L <: Loc, Label](l: L, label: Label @@ L, branches: Map[Label, Choreo[M, A]])
      extends ChoreoSig[M, A]

  case Par[M[_], A, B](left: Choreo[M, A], right: Choreo[M, B]) extends ChoreoSig[M, (A, B)]

extension [L <: Loc](l: L)
  def locally[M[_], A](m: Unwrap[l.type] ?=> M[A]): Choreo[M, A @@ l.type] =
    Free.liftF(ChoreoSig.Local[M, A, l.type](l, un => m(using un)))

  def send[A](a: A @@ L): Sendable[A, L] = (src = l, value = a)

  def asyncSend[A](a: A @@ L): AsyncSendable[A, L] = (src = l, value = a)

  def cond[M[_], A, B](a: A @@ L)(f: A => Choreo[M, B]): Choreo[M, B] =
    Free.liftF(ChoreoSig.Cond(l, a, f))

  def select[M[_], A, Label](label: Label @@ L)(branches: (Label, Choreo[M, A])*): Choreo[M, A] =
    Free.liftF(ChoreoSig.Select(l, label, branches.toMap))

opaque type Sendable[A, L <: Loc] = (src: L, value: A @@ L)

extension [A, Src <: Loc](s: Sendable[A, Src])
  def to[M[_], Dst <: Loc](dst: Dst): Choreo[M, A @@ dst.type] =
    Free.liftF(ChoreoSig.Comm(s.src, s.value, dst))

opaque type AsyncSendable[A, L <: Loc] = (src: L, value: A @@ L)

extension [A, Src <: Loc](s: AsyncSendable[A, Src])
  @targetName("asyncTo")
  def to[M[_], Dst <: Loc](dst: Dst): Choreo[M, M[A] @@ dst.type] =
    Free.liftF(ChoreoSig.AsyncComm(s.src, s.value, dst))

extension [M[_], A](c: Choreo[M, A])
  def runLocal(using M: Monad[M]): M[A] =
    c.foldMap(localHandler.toFunctionK)

  private[choreo] def localHandler(using
      M: Monad[M]
  ): [A] => ChoreoSig[M, A] => M[A] = [A] =>
    (c: ChoreoSig[M, A]) =>
      c match
        case ChoreoSig.Local(l, m) =>
          m(unwrap).map(wrap(_).asInstanceOf)

        case ChoreoSig.Comm(l0, a, l1) =>
          M.pure(wrap(unwrap(a)).asInstanceOf)

        case ChoreoSig.AsyncComm(l0, a, l1) =>
          M.pure(wrap(M.pure(unwrap(a))).asInstanceOf)

        case ChoreoSig.Cond(l, a, f) =>
          f(unwrap(a)).runLocal

        case ChoreoSig.Select(l, label, branches) =>
          branches(unwrap(label)).runLocal

        case ChoreoSig.Par(left, right) =>
          for
            a <- left.runLocal
            b <- right.runLocal
          yield (a, b)
