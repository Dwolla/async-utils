package com.dwolla.util.async.finagle

import cats.effect.*
import cats.effect.std.{Dispatcher, Env}
import cats.syntax.all.*
import com.dwolla.util.async.twitter.*
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.{Http, ListeningServer, Service, Thrift}
import com.twitter.util.Future
import example.thrift.SimpleService.{SimpleService as SimpleServiceAlg}
import example.thrift.{SimpleRequest, SimpleResponse, SimpleService}
import munit.CatsEffectSuite
import natchez.Trace

import java.net.{InetAddress, InetSocketAddress}
import scala.collection.immutable
import scala.concurrent.duration.*

class TracedThriftClientResourceSpec extends CatsEffectSuite {
  private implicit val noopTrace: Trace[IO] = Trace.Implicits.noop[IO]

  /**
   * A Thrift server listening on an ephemeral loopback port, which reports
   * its stats to `stats` so the tests can watch its open connections.
   */
  private case class StatsReportingServer(server: ListeningServer, stats: InMemoryStatsReceiver) {
    def dest: String = s"127.0.0.1:${port(server)}"

    /**
     * The number of connections open to the server, as reported by Finagle's `connections` gauge.
     */
    val openConnections: IO[Float] =
      IO(stats.gauges.get(Seq("connections")).map(_()))
        .flatMap(_.liftTo[IO](new NoSuchElementException(s"the server reported no connections gauge; it reported ${stats.gauges.keys}")))

    /**
     * Waits for the server's open connections to drop to zero, returning the
     * number still open if that doesn't happen in time.
     */
    val openConnectionsOnceAllClose: IO[Float] =
      openConnections
        .flatTap(n => IO.sleep(10.millis).whenA(n != 0))
        .iterateUntil(_ == 0)
        .timeoutTo(5.seconds, openConnections)
  }

  private def port(server: ListeningServer): Int =
    server.boundAddress.asInstanceOf[InetSocketAddress].getPort

  private val loopback: InetSocketAddress = new InetSocketAddress(InetAddress.getLoopbackAddress, 0)

  private val echoingService: SimpleService.MethodPerEndpoint =
    new SimpleService.MethodPerEndpoint {
      override def makeRequest(request: SimpleRequest): Future[SimpleResponse] =
        Future.value(SimpleResponse(request.id))
    }

  private val server: Resource[IO, StatsReportingServer] =
    IO(new InMemoryStatsReceiver).toResource.flatMap { stats =>
      Resource.make(IO {
        Thrift.server
          .withStatsReceiver(stats)
          .withTracer(NullTracer)
          .serveIface(loopback, echoingService)
      })(s => liftFuture[IO](IO(s.close())))
        .map(StatsReportingServer(_, stats))
    }

  private val serverFixture = ResourceFunFixture(server)

  /**
   * An HTTP server accepting whatever spans a Zipkin tracer sends it, so the
   * tracer has somewhere to send them, and the test can wait for it to do so.
   */
  private case class ZipkinCollector(server: ListeningServer, spansReceived: Deferred[IO, Unit]) {
    /**
     * An `Env` pointing Zipkin tracers built by `ZipkinTracer` at this collector.
     */
    val env: Env[IO] = new Env[IO] {
      private val variables = Map("OTEL_EXPORTER_ZIPKIN_ENDPOINT" -> s"127.0.0.1:${port(server)}")

      override def get(name: String): IO[Option[String]] = variables.get(name).pure[IO]
      override def entries: IO[immutable.Iterable[(String, String)]] = variables.pure[IO].widen
    }
  }

  private val zipkinCollector: Resource[IO, ZipkinCollector] =
    for {
      dispatcher <- Dispatcher.parallel[IO]
      spansReceived <- Deferred[IO, Unit].toResource
      server <- Resource.make(IO {
        Http.server
          .withTracer(NullTracer)
          .serve(loopback, Service.mk[Request, Response] { _ =>
            dispatcher.unsafeToTwitterFuture(spansReceived.complete(()).as(Response(Status.Accepted)))
          })
      })(s => liftFuture[IO](IO(s.close())))
    } yield ZipkinCollector(server, spansReceived)

  /**
   * Uses the client for one request, then releases it, returning the number of
   * connections open to the server while the client was in use and once it's released.
   */
  private def openConnectionsWhileInUseAndAfterRelease(server: StatsReportingServer,
                                                       client: Resource[IO, SimpleServiceAlg[IO]]): IO[(Float, Float)] =
    for {
      openWhileInUse <- client.use { c =>
        c.makeRequest(SimpleRequest("foo")).timeout(10.seconds) >> server.openConnections
      }
      openAfterRelease <- server.openConnectionsOnceAllClose
    } yield (openWhileInUse, openAfterRelease)

  serverFixture.test("releasing the client Resource built from a destination and configuration closes the Finagle client's connections") { server =>
    val client = TracedThriftClient[SimpleServiceAlg][IO, IO](server.dest, ThriftClientConfiguration().withTracer(NullTracer))

    assertIO(openConnectionsWhileInUseAndAfterRelease(server, client), (1.0f, 0.0f))
  }

  serverFixture.test("releasing the client Resource built from a service name and destination closes the Finagle client's connections") { server =>
    zipkinCollector.use { collector =>
      implicit val env: Env[IO] = collector.env
      val client = TracedThriftClient[SimpleServiceAlg][IO, IO]("foo-client", server.dest)

      // waiting for the client's span to reach the collector keeps the tracer from reporting it after the collector stops
      assertIO(openConnectionsWhileInUseAndAfterRelease(server, client), (1.0f, 0.0f)) >>
        collector.spansReceived.get.timeout(10.seconds)
    }
  }
}
