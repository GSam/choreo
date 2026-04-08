package choreo
package backend

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.std.Queue
import cats.effect.syntax.all.*
import cats.syntax.all.*

import java.io.{DataInputStream, DataOutputStream}
import java.net.{InetSocketAddress, ServerSocket, Socket}

import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}

import choreo.utils.toFunctionK

class TcpBackend[M[_]: Async] private (
    inboxes: Map[Channel, Queue[M, Any]],
    outputs: Map[Channel, DataOutputStream],
    val locs: Seq[Loc],
    codec: WireCodec
):

  def runNetwork[A](at: Loc)(
      network: Network[M, A]
  ): M[A] =
    network.foldMap(run(at).toFunctionK)

  private def run(
      at: Loc
  ): [A] => NetworkSig[M, A] => M[A] = [A] =>
    (na: NetworkSig[M, A]) =>
      na match
        case NetworkSig.Run(ma) =>
          ma

        case NetworkSig.Send(a, to) =>
          val out = outputs((from = at, to = to))
          Async[M].blocking {
            out.synchronized {
              val bytes = codec.encode(a)
              out.writeInt(bytes.length)
              out.write(bytes)
              out.flush()
            }
          }

        case NetworkSig.Recv(from) =>
          val inbox = inboxes((from = from, to = at))
          inbox.take.map(_.asInstanceOf[A])

        case NetworkSig.AsyncRecv(from) =>
          val inbox = inboxes((from = from, to = at))
          (for
            d <- Deferred[M, Any]
            _ <- Async[M].start(inbox.take.flatMap(v => d.complete(v).void))
          yield d.get).asInstanceOf[M[A]]

        case NetworkSig.Broadcast(a) =>
          locs
            .filter(_ != at)
            .traverse_ { to =>
              run(at)(NetworkSig.Send(a, to))
            }

        case NetworkSig.Par(left, right) =>
          val leftM  = left.foldMap(run(at).toFunctionK)
          val rightM = right.foldMap(run(at).toFunctionK)
          Async[M].both(leftM, rightM).asInstanceOf[M[A]]

object TcpBackend:

  /** Creates a cluster of TCP-connected locations in a single process.
    *
    * Each location gets its own server socket on localhost. Connections are established eagerly
    * between all pairs. Messages are serialized using the provided [[WireCodec]] over
    * length-prefixed binary frames.
    *
    * The returned Resource manages all sockets and background reader fibers.
    */
  def local[M[_]: Async](
      locs: List[Loc],
      codec: WireCodec = WireCodec.javaSerialization
  ): Resource[M, TcpBackend[M]] =
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
                _            <- readLoop(dis, inboxes((from = sender, to = loc)), codec).start
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
    yield TcpBackend(inboxes, outputs, locs, codec)

  private def readLoop[M[_]: Async](
      dis: DataInputStream,
      inbox: Queue[M, Any],
      codec: WireCodec
  ): M[Unit] =
    Async[M]
      .interruptible {
        val len = dis.readInt()
        val buf = new Array[Byte](len)
        dis.readFully(buf)
        codec.decode(buf)
      }
      .flatMap(inbox.offer)
      .foreverM
      .handleErrorWith {
        case _: java.io.EOFException     => Async[M].unit
        case _: java.net.SocketException => Async[M].unit
        case _: java.io.IOException      => Async[M].unit
        case e                           => Async[M].raiseError(e)
      }

  /** Creates a TCP backend for a single location in a distributed multi-machine deployment.
    *
    * Unlike [[local]], which spins up all locations inside one process, this method runs exactly
    * one location per process. Each location in the cluster must have a known socket address
    * supplied in `addresses`. The node binds a server socket at its own address and connects
    * to every peer. Connections to peers that are not yet available are retried with exponential
    * backoff so that nodes may start in any order.
    *
    * {{{
    *   val addresses = Map(
    *     "alice" -> new InetSocketAddress("10.0.0.1", 9000),
    *     "bob"   -> new InetSocketAddress("10.0.0.2", 9000)
    *   )
    *
    *   // On alice's machine:
    *   TcpBackend.distributed[IO](addresses, "alice").use { backend =>
    *     choreo.project(backend, "alice")
    *   }
    * }}}
    *
    * @param addresses       mapping from each location name to its socket address
    * @param myLoc           the location that this node will run
    * @param codec           wire codec for serializing messages (default: Java serialization)
    * @param connectRetries  maximum number of connection retry attempts per peer (default: 10)
    * @param initialDelay    initial delay before retrying a failed connection (default: 250ms),
    *                        doubled after each attempt
    */
  def distributed[M[_]: Async](
      addresses: Map[Loc, InetSocketAddress],
      myLoc: Loc,
      codec: WireCodec = WireCodec.javaSerialization,
      connectRetries: Int = 10,
      initialDelay: FiniteDuration = FiniteDuration(250, MILLISECONDS)
  ): Resource[M, TcpBackend[M]] =
    require(addresses.contains(myLoc), s"Address map must contain an entry for '$myLoc'")
    require(addresses.size >= 2, "Address map must contain at least two locations")

    val allLocs = addresses.keys.toList.sorted
    val peers   = allLocs.filter(_ != myLoc)

    for
      // 1. Bind server socket at our configured address
      server <- Resource.make(
                  Async[M].blocking {
                    val ss = new ServerSocket()
                    ss.setReuseAddress(true)
                    ss.bind(addresses(myLoc))
                    ss
                  }
                )(ss => Async[M].blocking(ss.close()))

      // Track accepted sockets so we can close them during resource finalization,
      // which unblocks any readLoop fibers still waiting on dis.readInt().
      acceptedSockets <- Resource.eval(Ref.of[M, List[Socket]](Nil))

      // 2. Create per-channel inboxes (channels where we are the receiver)
      inboxes <- Resource.eval {
                   peers
                     .traverse(p => Queue.unbounded[M, Any].map((from = p, to = myLoc) -> _))
                     .map(_.toMap)
                 }

      // 3. Accept connections from all peers (must run concurrently with step 4)
      ready <- Resource.eval(Deferred[M, Unit])

      _ <- {
        val acceptAll = peers
          .traverse_ { _ =>
            for
              result <- Async[M].blocking {
                          val socket = server.accept()
                          val dis    = new DataInputStream(socket.getInputStream)
                          val len    = dis.readInt()
                          val buf    = new Array[Byte](len)
                          dis.readFully(buf)
                          val sender = new String(buf, "UTF-8")
                          (sender, socket, dis)
                        }
              (sender, socket, dis) = result
              _ <- acceptedSockets.update(socket :: _)
              _ <- readLoop(dis, inboxes((from = sender, to = myLoc)), codec).start
            yield ()
          }
          .guarantee(ready.complete(()).void)

        acceptAll.background
      }

      // 4. Connect to every peer with exponential-backoff retry
      outputs <- peers
                   .traverse { peer =>
                     val peerAddr = addresses(peer)
                     Resource
                       .make(
                         connectWithRetry(peerAddr, myLoc, connectRetries, initialDelay)
                       ) { case (socket, _) =>
                         Async[M].blocking(socket.close())
                       }
                       .map { case (_, dos) => (from = myLoc, to = peer) -> dos }
                   }
                   .map(_.toMap)

      // 5. Wait for all acceptors to finish
      _ <- Resource.eval(ready.get)

      // 6. Ensure accepted sockets are closed on finalization
      _ <- Resource.onFinalize {
             acceptedSockets.get.flatMap {
               _.traverse_(s => Async[M].blocking(s.close()).handleError(_ => ()))
             }
           }
    yield TcpBackend(inboxes, outputs, allLocs, codec)

  private def connectWithRetry[M[_]: Async](
      addr: InetSocketAddress,
      myLoc: Loc,
      maxRetries: Int,
      delay: FiniteDuration
  ): M[(Socket, DataOutputStream)] =
    def attempt(remaining: Int, currentDelay: FiniteDuration): M[(Socket, DataOutputStream)] =
      Async[M]
        .blocking {
          val socket = new Socket()
          socket.connect(addr)
          val dos = new DataOutputStream(socket.getOutputStream)
          val id  = myLoc.getBytes("UTF-8")
          dos.writeInt(id.length)
          dos.write(id)
          dos.flush()
          (socket, dos)
        }
        .handleErrorWith { e =>
          if remaining <= 0 then
            Async[M].raiseError(
              new java.io.IOException(
                s"Failed to connect to $addr after $maxRetries attempts: ${e.getMessage}",
                e
              )
            )
          else Async[M].sleep(currentDelay) *> attempt(remaining - 1, currentDelay * 2)
        }
    attempt(maxRetries, delay)

  given tcpBackend[M[_]: Async]: Backend[TcpBackend[M], M] with
    extension (b: TcpBackend[M])
      def runNetwork[A](at: Loc)(network: Network[M, A]): M[A] =
        b.runNetwork(at)(network)
      def locs: Set[Loc]                                       = b.locs.toSet
