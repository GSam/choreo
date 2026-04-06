package choreo

import cats.Monad
import cats.free.Free
import cats.data.Writer
import cats.effect.IO
import cats.syntax.all.*
import cats.arrow.FunctionK
import choreo.utils.toFunctionK

enum NetworkSig[M[_], A]:
  case Run(ma: M[A])       extends NetworkSig[M, A]
  case Send(a: A, to: Loc) extends NetworkSig[M, Unit]
  case Recv(from: Loc)     extends NetworkSig[M, A]
  case Broadcast(a: A)     extends NetworkSig[M, Unit]
  case Par[M[_], A, B](left: Network[M, A], right: Network[M, B]) extends NetworkSig[M, (A, B)]

type Network[M[_], A] = Free[[X] =>> NetworkSig[M, X], A]

object Network:
  def pure[M[_], A](a: A): Network[M, A] =
    Free.pure(a)

  def run[M[_], A](ma: M[A]): Network[M, A] =
    Free.liftF(NetworkSig.Run(ma))

  def send[M[_], A](a: A, to: Loc): Network[M, Unit] =
    Free.liftF(NetworkSig.Send(a, to))

  def recv[M[_], A](from: Loc): Network[M, A] =
    Free.liftF(NetworkSig.Recv(from))

  def broadcast[M[_], A](a: A): Network[M, Unit] =
    Free.liftF(NetworkSig.Broadcast(a))

  def par[M[_], A, B](left: Network[M, A], right: Network[M, B]): Network[M, (A, B)] =
    Free.liftF(NetworkSig.Par(left, right))

  def empty[M[_], A, L <: Loc]: Network[M, A @@ L] =
    Network.pure(At.empty[A, L])

/** Placeholder value for locations not involved in an operation.
  *
  * During endpoint projection, non-participating locations must still produce a
  * value of the expected type to satisfy the Free monad. This value is never
  * observed by user code.
  */
private def uninhabited[A]: A = null.asInstanceOf[A]

object Endpoint:
  def project[M[_], A](c: Choreo[M, A], at: Loc, locs: Set[Loc]): Network[M, A] =
    c.foldMap(epp[M](at, locs).toFunctionK)

  private[choreo] def epp[M[_]](
      at: Loc,
      locs: Set[Loc]
  ): [A] => ChoreoSig[M, A] => Network[M, A] = [A] =>
    (c: ChoreoSig[M, A]) =>
      c match
        case ChoreoSig.Local(loc, m) =>
          if at == loc then Network.run(m(unwrap)).map(wrap.asInstanceOf)
          else Network.empty.asInstanceOf

        case ChoreoSig.Comm(src, a, dst) =>
          if at == src then Network.send(unwrap(a), dst) *> Network.empty.asInstanceOf
          else if at == dst then Network.recv(src).map(wrap.asInstanceOf)
          else Network.empty[M, a.Value, a.Location]

        case ChoreoSig.Cond(loc, a, f) =>
          if at == loc then
            val value    = unwrap(a)
            val branch   = f(value)
            val involved = collectLocations(branch, locs)
            val sends    = (locs - loc).toList.traverse_ { l =>
              if involved.contains(l) then Network.send[M, Any](Some(value), l)
              else Network.send[M, Any](None, l)
            }
            sends *> project(branch, at, locs)
          else
            Network.recv[M, Any](loc).flatMap {
              case Some(value) => project(f(value.asInstanceOf), at, locs)
              case None        => Free.pure(uninhabited[A])
              case value       => project(f(value.asInstanceOf), at, locs)
            }

        case ChoreoSig.Select(loc, label, branches) =>
          val allInvolved = branches.values.foldLeft(Set.empty[Loc]) { (acc, b) =>
            acc ++ collectLocations(b, locs)
          }
          if at == loc then
            val key    = unwrap(label)
            val branch = branches(key)
            val sends  = (allInvolved - loc).toList.traverse_ { l =>
              Network.send[M, Any](key, l)
            }
            sends *> project(branch, at, locs)
          else if allInvolved.contains(at) then
            Network.recv[M, Any](loc).flatMap { key =>
              project(branches(key.asInstanceOf), at, locs)
            }
          else
            Free.pure(uninhabited[A])

        case ChoreoSig.Par(left, right) =>
          val locsLeft  = collectLocations(left, locs)
          val locsRight = collectLocations(right, locs)
          val leftNet   =
            if locsLeft.contains(at) then project(left, at, locsLeft)
            else Free.pure(uninhabited)
          val rightNet  =
            if locsRight.contains(at) then project(right, at, locsRight)
            else Free.pure(uninhabited)
          Network.par(leftNet, rightNet).asInstanceOf[Network[M, A]]

  private[choreo] def collectLocations[M[_], A](
      c: Choreo[M, A],
      allLocs: Set[Loc]
  ): Set[Loc] =
    type W[X] = Writer[Set[Loc], X]

    val collector: [X] => ChoreoSig[M, X] => W[X] = [X] =>
      (sig: ChoreoSig[M, X]) =>
        sig match
          case ChoreoSig.Local(loc, _) =>
            Writer(Set[Loc](loc), At.empty.asInstanceOf[X])

          case ChoreoSig.Comm(src, _, dst) =>
            Writer(Set[Loc](src, dst), At.empty.asInstanceOf[X])

          case ChoreoSig.Cond(loc, a, f) =>
            val innerLocs = a match
              case At.Wrap(v) => collectLocations(f(v), allLocs)
              case _          => allLocs
            Writer(Set[Loc](loc) ++ innerLocs, uninhabited[X])

          case ChoreoSig.Select(loc, _, branches) =>
            val innerLocs = branches.values.foldLeft(Set.empty[Loc]) { (acc, b) =>
              acc ++ collectLocations(b, allLocs)
            }
            Writer(Set[Loc](loc) ++ innerLocs, uninhabited[X])

          case ChoreoSig.Par(left, right) =>
            val locs = collectLocations(left, allLocs) ++ collectLocations(right, allLocs)
            Writer(locs, uninhabited[X])

    c.foldMap(collector.toFunctionK).written
