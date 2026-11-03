package com.dwolla.util.async.http4s

import cats.effect._
import cats.syntax.all._
import com.comcast.ip4s._
import com.dwolla.util.async.http4s.RawHttpClient._
import fs2.io.net.Network
import munit.CatsEffectSuite
import org.http4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.http4s.syntax.all._

import scala.concurrent.duration._

/**
 * GracefulShutdown's pieces assembled around a real Ember server, whose release is begun the way a stopping
 * service's would be. Clients talk to it over raw sockets so each test controls keep-alive exactly and sees what
 * the server does with the connection itself.
 *
 * These tests use real sockets and wall-clock time: `TestControl` can't drive them, because socket I/O completes
 * outside its runtime and it would advance virtual time (ending the drain window and firing Ember's shutdown
 * timeout) whenever every fiber is waiting on the network.
 */
class EmberGracefulShutdownSpec extends CatsEffectSuite {
  import EmberGracefulShutdownSpec._

  private val drainPeriod = 2.seconds
  private val shutdownTimeout = 2.seconds

  /** Leeway for scheduling and socket I/O when asserting that something happened by a deadline. */
  private val slack = 1.second

  /** How long the test waits for anything to happen on a socket: long enough to outlast the whole release. */
  private val observationWindow = drainPeriod + shutdownTimeout + slack

  /** Starts inside the drain window and is still running when it ends, but finishes within the shutdown timeout. */
  private val slowRequestStartsAt = 1.second
  private val slowRequestDuration = 1500.millis

  private val healthRequest = keepAliveGet("/health")
  private val slowRequest = keepAliveGet("/slow")

  private val slowRoute: HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case req if req.method == Method.GET && req.pathInfo == path"/slow" =>
        IO.sleep(slowRequestDuration).as(Response[IO](Status.Ok))
    }

  private def server(signal: DrainSignal[IO]): Resource[IO, Server] =
    GracefulShutdown.drainBeforeRelease(signal, drainPeriod) {
      EmberServerBuilder
        .default[IO]
        .withHost(host"127.0.0.1")
        .withPort(port"0")
        .withShutdownTimeout(shutdownTimeout)
        .withHttpApp(GracefulShutdown.closeConnectionsWhileDraining(signal)((GracefulShutdown.healthRoutes(signal) <+> slowRoute).orNotFound))
        .build
    }

  /**
   * Starts a server and runs `test` with it and an action that begins its release in the background. The release
   * is awaited after `test`, whatever its outcome, and begun first if `test` didn't begin it.
   */
  private def withServer[A](test: (Server, IO[Release]) => IO[A]): IO[A] =
    DrainSignal[IO].flatMap { signal =>
      server(signal).allocated.flatMap { case (server, release) =>
        beginRelease(signal, release).memoize.flatMap { begin =>
          test(server, begin).guarantee(begin.flatMap(_.finished))
        }
      }
    }

  /** Begins `release` in the background, returning once the drain window has opened. */
  private def beginRelease(signal: DrainSignal[IO], release: IO[Unit]): IO[Release] =
    for {
      began <- IO.monotonic
      fiber <- release.start
      _ <- (IO.sleep(10.millis) >> signal.isDraining).iterateUntil(identity).timeout(slack)
    } yield Release(IO.monotonic.map(_ - began), fiber.joinWithNever)

  test("during the drain window a new connection's /health gets 503 with Connection: close") {
    withServer { (server, beginRelease) =>
      for {
        release <- beginRelease
        reply <- requestOnNewConnection(server, healthRequest, observationWindow)
        repliedAt <- release.sinceBegan
      } yield {
        assertEquals(reply, Reply.Answered("HTTP/1.1 503 Service Unavailable", "close".some))
        assert(repliedAt < drainPeriod, s"replied ${repliedAt.toMillis} ms after release began, after the drain window")
      }
    }
  }

  test("during the drain window a request on an existing keep-alive connection is answered with Connection: close and the server closes the connection") {
    withServer { (server, beginRelease) =>
      Network[IO].connect(server.addressIp4s).use { connection =>
        for {
          _ <- connection.write(healthRequest)
          beforeRelease <- awaitReply(connection, observationWindow)
          release <- beginRelease
          _ <- connection.write(healthRequest)
          duringDrain <- awaitReply(connection, observationWindow)
          fate <- connectionFate(connection, observationWindow)
          closedAt <- release.sinceBegan
        } yield {
          assertEquals(beforeRelease, Reply.Answered("HTTP/1.1 204 No Content", "keep-alive".some))
          assertEquals(duringDrain, Reply.Answered("HTTP/1.1 503 Service Unavailable", "close".some))
          assertEquals(fate, ConnectionFate.ClosedByServer)
          assert(closedAt < drainPeriod, s"closed ${closedAt.toMillis} ms after release began, after the drain window")
        }
      }
    }
  }

  // The idle keep-alive connection makes Ember's shutdown wait out the whole shutdown timeout, during which its
  // listener is still bound but no longer accepting, so new connections hang until the shutdown finishes.
  test("after the drain window and shutdown timeout, new connections are refused") {
    withServer { (server, beginRelease) =>
      Network[IO].connect(server.addressIp4s).use { idleConnection =>
        for {
          _ <- idleConnection.write(healthRequest)
          beforeRelease <- awaitReply(idleConnection, observationWindow)
          release <- beginRelease
          _ <- release.finished
          finishedAt <- release.sinceBegan
          reply <- requestOnNewConnection(server, healthRequest, observationWindow)
        } yield {
          assertEquals(beforeRelease, Reply.Answered("HTTP/1.1 204 No Content", "keep-alive".some))
          assert(
            finishedAt < drainPeriod + shutdownTimeout + slack,
            s"release finished ${finishedAt.toMillis} ms after it began, well past the drain period plus shutdown timeout",
          )
          reply match {
            case Reply.Refused(_) => ()
            case other => fail(s"a new connection after release finished should be refused, but got $other")
          }
        }
      }
    }
  }

  test("a request still running when the drain window ends is answered") {
    withServer { (server, beginRelease) =>
      for {
        release <- beginRelease
        _ <- release.sinceBegan.flatMap(elapsed => IO.sleep(slowRequestStartsAt - elapsed))
        reply <- requestOnNewConnection(server, slowRequest, observationWindow)
        repliedAt <- release.sinceBegan
      } yield {
        assertEquals(reply, Reply.Answered("HTTP/1.1 200 OK", "close".some), s"${repliedAt.toMillis} ms after release began")
        assert(repliedAt >= drainPeriod, s"replied ${repliedAt.toMillis} ms after release began, so the request had finished before the drain window ended")
      }
    }
  }

  test("a keep-alive connection idle through the drain window is closed by the server at the shutdown timeout") {
    withServer { (server, beginRelease) =>
      Network[IO].connect(server.addressIp4s).use { idleConnection =>
        for {
          _ <- idleConnection.write(healthRequest)
          beforeRelease <- awaitReply(idleConnection, observationWindow)
          release <- beginRelease
          fate <- connectionFate(idleConnection, observationWindow)
          closedAt <- release.sinceBegan
        } yield {
          assertEquals(beforeRelease, Reply.Answered("HTTP/1.1 204 No Content", "keep-alive".some))
          assertEquals(fate, ConnectionFate.ClosedByServer, s"${closedAt.toMillis} ms after release began")
          assert(
            closedAt >= drainPeriod + shutdownTimeout && closedAt < drainPeriod + shutdownTimeout + slack,
            s"closed ${closedAt.toMillis} ms after release began; expected at the drain period plus shutdown timeout, ${(drainPeriod + shutdownTimeout).toMillis} ms",
          )
        }
      }
    }
  }
}

object EmberGracefulShutdownSpec {
  /** A server release running in the background: the time since it began, and an action that awaits its end. */
  private final case class Release(sinceBegan: IO[FiniteDuration], finished: IO[Unit])
}
