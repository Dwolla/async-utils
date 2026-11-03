package com.dwolla.util.async.finagle

import cats.effect.*
import cats.mtl.Local
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.dwolla.util.async.twitter.*
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.{Flags, NullTracer, SpanId, TraceId, Trace as FinagleTrace}
import com.twitter.finagle.{ListeningServer, Thrift}
import example.thrift.SimpleService.{SimpleService as SimpleServiceAlg}
import example.thrift.{SimpleRequest, SimpleResponse, SimpleService}
import munit.CatsEffectSuite
import natchez.{EntryPoint, Span}

import java.net.{InetSocketAddress, Socket, SocketException}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

class TracedThriftServerShutdownSpec extends CatsEffectSuite {
  private implicit val ec: ExecutionContext = munitExecutionContext

  private val loopback: SocketAddress[IpAddress] = SocketAddress(ip"127.0.0.1", port"0")

  private val heldRequestId = "held"

  /**
   * A Thrift implementation that holds each call until `finish` is completed, reporting through
   * `started` when it begins, then echoes the call's ID.
   */
  private def holdingService(started: Deferred[IO, Unit], finish: Deferred[IO, Unit]): SimpleServiceAlg[IO] =
    new SimpleServiceAlg[IO] {
      override def makeRequest(request: SimpleRequest): IO[SimpleResponse] =
        (started.complete(()) >> finish.get).as(SimpleResponse(request.id))
    }

  private def tracedServer(service: SimpleServiceAlg[IO]): Resource[IO, ListeningServer] =
    IOLocal(Span.noop[IO]).toResource.flatMap { currentSpan =>
      implicit val local: Local[IO, Span[IO]] = currentSpan.asLocal
      TracedThriftServer[IO, SimpleServiceAlg](loopback, "foo-service", service, EntryPoint.noop[IO])
    }

  /**
   * Acquires the server and hands the test the action that releases it. The release runs at
   * most once: when the test runs it, or when this `Resource` is released if the test didn't.
   */
  private def releasableByTest(server: Resource[IO, ListeningServer]): Resource[IO, (ListeningServer, IO[Unit])] =
    Resource.make(server.allocated.flatMap { case (s, release) => release.memoize.tupleLeft(s) })(_._2)

  private def client(server: ListeningServer): Resource[IO, SimpleService.MethodPerEndpoint] =
    Resource.make(IO {
      Thrift.client
        .withTracer(NullTracer)
        .withStatsReceiver(NullStatsReceiver)
        .build[SimpleService.MethodPerEndpoint](s"127.0.0.1:${server.boundAddress.asInstanceOf[InetSocketAddress].getPort}")
    })(c => liftFuture[IO](IO(c.asClosable.close())))

  /**
   * The server's Finagle Zipkin tracer reports only sampled traces, so sending the call in a trace
   * marked as not sampled keeps it from reporting spans to a Zipkin collector that isn't there.
   */
  private val unsampledTraceId: TraceId =
    TraceId(SpanId(1L).some, None, SpanId(1L), false.some, Flags())

  private def call(client: SimpleService.MethodPerEndpoint, requestId: String): IO[SimpleResponse] =
    liftFuture[IO](IO(FinagleTrace.letId(unsampledTraceId, terminal = true)(client.makeRequest(SimpleRequest(requestId)))))

  /**
   * Waits until the server stops accepting new TCP connections, which shows it has started closing.
   * A connection made just as the listener closes can be reset instead of refused, so a reset counts
   * as not accepting too. It connects with a plain socket rather than a Finagle client, which would
   * log such a reset as a channel error.
   */
  private def awaitConnectionsRefused(server: ListeningServer): IO[Unit] =
    Resource.fromAutoCloseable(IO(new Socket()))
      .use(socket => IO.blocking(socket.connect(server.boundAddress, 1000)))
      .as(true)
      .recover { case _: SocketException => false }
      .iterateWhile(identity)
      .timeout(10.seconds)
      .void

  test("a call in flight when the server is released still succeeds") {
    (Deferred[IO, Unit], Deferred[IO, Unit]).flatMapN { (started, finish) =>
      releasableByTest(tracedServer(holdingService(started, finish))).use { case (server, release) =>
        client(server).use { c =>
          for {
            heldCall <- call(c, heldRequestId).attempt.start
            _ <- started.get.timeout(10.seconds)
            releasing <- release.start
            _ <- awaitConnectionsRefused(server)
            // Finagle closes a busy connection on its timer once the close deadline passes, a few milliseconds
            // after the listener closes even when the deadline has already passed, so keep the call in flight a while
            _ <- IO.sleep(250.millis)
            _ <- finish.complete(())
            response <- heldCall.joinWithNever.timeout(10.seconds)
            _ <- releasing.joinWithNever.timeout(10.seconds)
          } yield assertEquals(response, Right(SimpleResponse(heldRequestId)))
        }
      }
    }
  }
}
