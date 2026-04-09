package choreo
package examples
package echo

import cats.effect.{IO, IOApp}
import cats.effect.IO.asyncForIO
import cats.syntax.all.*

import java.net.InetSocketAddress
import java.net.ServerSocket

import choreo.backend.TcpBackend

val serverLoc: "server" = "server"
val clientLoc: "client" = "client"

val serverAddress = new InetSocketAddress("0.0.0.0", 9010)
val clientAddress = new InetSocketAddress("127.0.0.1", 9010)

def protocol: Choreo[IO, Unit] =
  for
    msgC <- clientLoc.locally:
              IO.print("> ") *> IO.readLine.map(s => Option(s).getOrElse("quit"))
    msgS <- clientLoc.send(msgC).to(serverLoc)

    _ <- serverLoc.locally:
           IO.println(s"[server] received: ${msgS.!}")

    continue <- serverLoc.locally:
                  IO.pure(msgS.! != "quit")

    _ <- serverLoc.cond(continue) {
           case true =>
             for
               replyS <- serverLoc.locally(IO.pure(s"echo: ${msgS.!}"))
               replyC <- serverLoc.send(replyS).to(clientLoc)
               _      <- clientLoc.locally(IO.println(replyC.!))
               _      <- protocol
             yield ()

           case false =>
             for
               _ <- serverLoc.locally(IO.println("[server] Client disconnected."))
               _ <- clientLoc.locally(IO.println("Goodbye!"))
             yield ()
         }
  yield ()

def logError(prefix: String)(t: Throwable): IO[Unit] =
  IO.println(s"$prefix ${t.getClass.getName}: ${t.getMessage}") *>
    IO.blocking(t.printStackTrace())

/** Single-process demo: server + 3 automated clients in one process. */
object echoDemo extends IOApp.Simple:
  val autoProtocol: Choreo[IO, String @@ "client"] =
    for
      a <- clientLoc.locally(IO.pure("hello"))
      b <- clientLoc.send(a).to(serverLoc)
      c <- serverLoc.locally(IO.pure(s"echo: ${b.!}"))
      d <- serverLoc.send(c).to(clientLoc)
    yield d

  def run: IO[Unit] =
    for
      port <- IO.blocking {
                val ss = new ServerSocket(0)
                val p  = ss.getLocalPort
                ss.close()
                p
              }
      addr = new InetSocketAddress("localhost", port)
      _ <- IO.println(s"[main] Using port $port")
      results <- TcpBackend.server[IO](addr, serverLoc, clientLoc) { backend =>
                   autoProtocol.project(backend, serverLoc).void
                 }.use { _ =>
                   val connectAndRun = TcpBackend.connect[IO](addr, serverLoc, clientLoc).use { backend =>
                     autoProtocol.project(backend, clientLoc).map(unwrap[clientLoc.type](_))
                   }
                   (connectAndRun, connectAndRun, connectAndRun).parTupled.flatMap { case (a, b, c) =>
                     IO.println(s"Results: $a, $b, $c")
                   }
                 }
    yield ()

/** Distributed server — run in its own terminal. */
object echoServer extends IOApp.Simple:
  def run: IO[Unit] =
    IO.println(s"[server] Listening on $serverAddress ...") *>
      TcpBackend.server[IO](serverAddress, serverLoc, clientLoc) { backend =>
        IO.println("[server] session started") *>
          protocol.project(backend, serverLoc).handleErrorWith(logError("[server] error")).void
      }.use(_ => IO.never)

/** Distributed client — run in its own terminal. */
object echoClient extends IOApp.Simple:
  def run: IO[Unit] =
    TcpBackend.connect[IO](clientAddress, serverLoc, clientLoc).use { backend =>
      IO.println("[client] Connected to server.") *>
        protocol.project(backend, clientLoc).handleErrorWith(logError("[client] error")).void
    }
