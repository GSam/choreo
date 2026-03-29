package choreo
package backend

import cats.Monad
import cats.effect.{Async, Deferred, Resource}
import cats.effect.std.Queue
import cats.effect.syntax.all.*
import cats.syntax.all.*

import java.io.{
  ByteArrayInputStream,
  ByteArrayOutputStream,
  DataInputStream,
  DataOutputStream,
  ObjectInputStream,
  ObjectOutputStream
}
import java.net.{InetSocketAddress, ServerSocket, Socket}

import choreo.utils.toFunctionK

class TcpBackend[M[_]: Async] private (
    inboxes: Map[Channel, Queue[M, Any]],
    outputs: Map[Channel, DataOutputStream],
    val locs: Seq[Loc]
):

  def runNetwork[A](at: Loc)(
      network: Network[M, A]
  )(using M: Monad[M]): M[A] =
    network.foldMap(run(at).toFunctionK)

  private def run(
      at: Loc
  )(using M: Monad[M]): [A] => NetworkSig[M, A] => M[A] = [A] =>
    (na: NetworkSig[M, A]) =>
      na match
        case NetworkSig.Run(ma) =>
          ma

        case NetworkSig.Send(a, to) =>
          val out = outputs((from = at, to = to))
          Async[M].blocking {
            out.synchronized {
              val baos  = new ByteArrayOutputStream()
              val oos   = new ObjectOutputStream(baos)
              oos.writeObject(a)
              oos.flush()
              val bytes = baos.toByteArray
              out.writeInt(bytes.length)
              out.write(bytes)
              out.flush()
            }
          }

        case NetworkSig.Recv(from) =>
          val inbox = inboxes((from = from, to = at))
          inbox.take.map(_.asInstanceOf[A])

        case NetworkSig.Broadcast(a) =>
          locs
            .filter(_ != at)
            .traverse_ { to =>
              run(at)(NetworkSig.Send(a, to))
            }

object TcpBackend:

  /** Creates a cluster of TCP-connected locations in a single process.
    *
    * Each location gets its own server socket on localhost. Connections are established eagerly
    * between all pairs. Messages are serialized using Java ObjectOutputStream over length-prefixed
    * binary frames.
    *
    * The returned Resource manages all sockets and background reader fibers.
    */
  def local[M[_]: Async](locs: List[Loc]): Resource[M, TcpBackend[M]] =
    val channels = for { s <- locs; r <- locs; if s != r } yield (from = s, to = r)

    for
      // 1. Bind a server socket per location (port 0 = OS-assigned)
      servers <-
        locs
          .traverse { loc =>
            Resource
              .make(Async[M].blocking(new ServerSocket(0)))(ss => Async[M].blocking(ss.close()))
              .map(loc -> _)
          }
          .map(_.toMap)

      // 2. Create per-channel message inboxes
      inboxes <- Resource.eval(
                   channels.traverse(ch => Queue.unbounded[M, Any].map(ch -> _)).map(_.toMap)
                 )

      // 3. Start acceptor fibers in background, then connect clients.
      //    Acceptors must run concurrently with connectors to avoid deadlock
      //    (both accept() and connect() are blocking).
      ready <- Resource.eval(Deferred[M, Unit])

      _ <- {
        val acceptAll = servers.toList
          .traverse_ { case (loc, server) =>
            (0 until (locs.size - 1)).toList.traverse_ { _ =>
              for
                result       <- Async[M].blocking {
                                  val socket = server.accept()
                                  val dis    = new DataInputStream(socket.getInputStream)
                                  val len    = dis.readInt()
                                  val buf    = new Array[Byte](len)
                                  dis.readFully(buf)
                                  val sender = new String(buf, "UTF-8")
                                  (sender, dis)
                                }
                (sender, dis) = result
                _            <- readLoop(dis, inboxes((from = sender, to = loc))).start
              yield ()
            }
          }
          .guarantee(ready.complete(()).void)

        acceptAll.background
      }

      // 4. Connect to every peer
      outputs <- channels
                   .traverse { ch =>
                     val port = servers(ch.to).getLocalPort
                     Resource
                       .make(
                         Async[M].blocking {
                           val socket = new Socket()
                           socket.connect(new InetSocketAddress("localhost", port))
                           val dos    = new DataOutputStream(socket.getOutputStream)
                           // Identify ourselves with a length-prefixed string
                           val id     = ch.from.getBytes("UTF-8")
                           dos.writeInt(id.length)
                           dos.write(id)
                           dos.flush()
                           (ch, socket, dos)
                         }
                       ) { case (_, socket, _) =>
                         Async[M].blocking(socket.close())
                       }
                   }
                   .map(_.map { case (ch, _, dos) => ch -> dos }.toMap)

      // 5. Wait for all acceptors to finish
      _ <- Resource.eval(ready.get)
    yield TcpBackend(inboxes, outputs, locs)

  private def readLoop[M[_]: Async](
      dis: DataInputStream,
      inbox: Queue[M, Any]
  ): M[Unit] =
    Async[M]
      .blocking {
        val len = dis.readInt()
        val buf = new Array[Byte](len)
        dis.readFully(buf)
        val ois = new ObjectInputStream(new ByteArrayInputStream(buf))
        ois.readObject()
      }
      .flatMap(inbox.offer)
      .foreverM
      .handleErrorWith {
        case _: java.io.EOFException => Async[M].unit
        case e                       => Async[M].raiseError(e)
      }

  given tcpBackend[M[_]: Async]: Backend[TcpBackend[M], M] with
    extension (b: TcpBackend[M])
      def runNetwork[A](at: Loc)(network: Network[M, A]): M[A] =
        b.runNetwork(at)(network)
      def locs: Set[Loc]                                       = b.locs.toSet
