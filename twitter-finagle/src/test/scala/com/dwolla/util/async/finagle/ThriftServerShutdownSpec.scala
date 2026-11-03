package com.dwolla.util.async.finagle

import cats.effect.*
import cats.syntax.all.*
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.{ConnectionFailedException, Failure, FailureFlags, ListeningServer}
import example.thrift.SimpleService.{SimpleService as SimpleServiceAlg}
import example.thrift.{SimpleRequest, SimpleResponse}
import munit.CatsEffectSuite

import java.net.{InetSocketAddress, Socket, SocketException}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

class ThriftServerShutdownSpec extends CatsEffectSuite {
  private implicit val ec: ExecutionContext = munitExecutionContext

  private val heldRequestId = "held"

  /**
   * Latches for the one call the test holds in flight: the handler reports when the call
   * starts and when it's canceled, and responds only once `finish` is completed.
   */
  private case class HeldCall(started: Deferred[IO, Unit],
                              finish: Deferred[IO, Unit],
                              canceled: Deferred[IO, Unit])

  private object HeldCall {
    val make: IO[HeldCall] =
      (Deferred[IO, Unit], Deferred[IO, Unit], Deferred[IO, Unit]).mapN(HeldCall(_, _, _))
  }

  /**
   * A Thrift implementation that holds the call with `heldRequestId` until the test finishes it,
   * and answers any other call right away, echoing its ID.
   */
  private def holdingService(held: HeldCall): SimpleServiceAlg[IO] = new SimpleServiceAlg[IO] {
    override def makeRequest(request: SimpleRequest): IO[SimpleResponse] =
      if (request.id == heldRequestId)
        (held.started.complete(()) >> held.finish.get)
          .as(SimpleResponse(request.id))
          .onCancel(held.canceled.complete(()).void)
      else SimpleResponse(request.id).pure[IO]
  }

  /**
   * A `ThriftServer` on an ephemeral loopback port, with its `Resource` already acquired so the
   * test decides when to release it. `release` runs at most once; the fixture runs it at the end
   * of the test if the test didn't.
   */
  private case class ServerUnderTest(server: ListeningServer, release: IO[Unit], held: HeldCall) {
    def dest: String = s"127.0.0.1:${server.boundAddress.asInstanceOf[InetSocketAddress].getPort}"
  }

  private val serverUnderTest: Resource[IO, ServerUnderTest] =
    for {
      held <- HeldCall.make.toResource
      serverAndRelease <- Resource.make {
        ThriftServer[IO, SimpleServiceAlg]("127.0.0.1:0", holdingService(held))
          .allocated
          .flatMap { case (server, release) => release.memoize.tupleLeft(server) }
      }(_._2)
    } yield ServerUnderTest(serverAndRelease._1, serverAndRelease._2, held)

  private val serverFixture = ResourceFunFixture(serverUnderTest)

  private val clientConfig: ThriftClientConfiguration =
    ThriftClientConfiguration()
      .withTracer(NullTracer)
      .withStatsReceiver(NullStatsReceiver)

  private def client(dest: String): Resource[IO, SimpleServiceAlg[IO]] =
    ThriftClient[SimpleServiceAlg][IO](dest, clientConfig)

  /**
   * Starts the held call on its own fiber and waits until the server is handling it. The fiber
   * returns the call's failure rather than failing, so a failed call is reported by the test's
   * assertions alone.
   */
  private def startHeldCall(f: ServerUnderTest, client: SimpleServiceAlg[IO]): IO[FiberIO[Either[Throwable, SimpleResponse]]] =
    client.makeRequest(SimpleRequest(heldRequestId)).attempt.start <* f.held.started.get.timeout(10.seconds)

  /**
   * Whether the server accepts a TCP connection. A connection made just as the listener closes
   * can be reset instead of refused, so a reset counts as not accepting too. It uses a plain
   * socket rather than a Finagle client, which would log such a reset as a channel error.
   */
  private def acceptsConnections(f: ServerUnderTest): IO[Boolean] =
    Resource.fromAutoCloseable(IO(new Socket()))
      .use(socket => IO.blocking(socket.connect(f.server.boundAddress, 1000)))
      .as(true)
      .recover { case _: SocketException => false }

  /**
   * Starts `release` on its own fiber, and waits until the server refuses new connections,
   * which shows the release has started closing it.
   */
  private def startReleasing[A](f: ServerUnderTest, release: IO[A]): IO[FiberIO[A]] =
    release.start <* acceptsConnections(f).iterateWhile(identity).timeout(10.seconds)

  /**
   * Keeps the held call in flight a while after release starts. Finagle closes a busy connection
   * on its timer once the close deadline passes, which is a few milliseconds after the listener
   * closes even when the deadline has already passed, so a call answered at once could be written
   * before a close that didn't wait for it.
   */
  private val keepInFlightWhileReleasing: IO[Unit] = IO.sleep(250.millis)

  /**
   * Whether the failure is how a Finagle client reports that the server refused its connection.
   */
  private def isRefusedConnection(t: Throwable): Boolean =
    t match {
      case _: ConnectionFailedException => true
      case f: Failure => f.isFlagged(FailureFlags.Rejected) && f.cause.exists(_.isInstanceOf[ConnectionFailedException])
      case _ => false
    }

  serverFixture.test("a call in flight when the server is released still succeeds") { f =>
    client(f.dest).use { c =>
      for {
        call <- startHeldCall(f, c)
        release <- startReleasing(f, f.release)
        _ <- keepInFlightWhileReleasing
        _ <- f.held.finish.complete(())
        response <- call.joinWithNever.timeout(10.seconds)
        _ <- release.joinWithNever.timeout(10.seconds)
      } yield assertEquals(response, Right(SimpleResponse(heldRequestId)))
    }
  }

  serverFixture.test("new connections are refused once release starts") { f =>
    client(f.dest).use { c =>
      for {
        call <- startHeldCall(f, c)
        releaseFinished <- Deferred[IO, Unit]
        _ <- startReleasing(f, f.release.guarantee(releaseFinished.complete(()).void))
        refused <- client(f.dest).use(_.makeRequest(SimpleRequest("on a new connection"))).timeout(10.seconds).attempt
        releaseFinishedBeforeRefusal <- releaseFinished.tryGet
        _ <- f.held.finish.complete(())
        response <- call.joinWithNever.timeout(10.seconds)
        _ <- releaseFinished.get.timeout(10.seconds)
      } yield {
        assertEquals(response, Right(SimpleResponse(heldRequestId)))
        assertEquals(releaseFinishedBeforeRefusal, None, "the held call should still be keeping release open")
        assert(refused.left.exists(isRefusedConnection), s"expected a refused connection, but got $refused")
      }
    }
  }

  serverFixture.test("release finishes when in-flight work does, not at the grace period") { f =>
    client(f.dest).use { c =>
      for {
        call <- startHeldCall(f, c)
        release <- startReleasing(f, f.release.timed)
        _ <- IO.sleep(500.millis)
        _ <- f.held.finish.complete(())
        releaseDuration <- release.joinWithNever.timeout(10.seconds).map(_._1)
        response <- call.joinWithNever.timeout(10.seconds)
      } yield {
        assertEquals(response, Right(SimpleResponse(heldRequestId)))
        assert(releaseDuration >= 500.millis, s"release took $releaseDuration, so it didn't wait for the call in flight")
        assert(releaseDuration < 5.seconds, s"release took $releaseDuration")
      }
    }
  }

  serverFixture.test("a call still running at the grace period is interrupted and its effect canceled") { f =>
    client(f.dest).use { c =>
      for {
        call <- startHeldCall(f, c)
        releaseDuration <- ThriftServer.closeGracefully[IO](f.server, 1.second).timed.timeout(10.seconds).map(_._1)
        _ <- f.held.canceled.get.timeout(5.seconds)
        response <- call.joinWithNever.timeout(10.seconds)
      } yield {
        assert(releaseDuration < 5.seconds, s"release took $releaseDuration")
        assert(response.isLeft, s"the client should see the interrupted call fail, but it got $response")
      }
    }
  }
}
