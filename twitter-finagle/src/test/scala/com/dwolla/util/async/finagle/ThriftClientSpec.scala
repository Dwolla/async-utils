package com.dwolla.util.async.finagle

import cats.effect.*
import cats.syntax.all.*
import com.dwolla.util.async.twitter.*
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.finagle.{ListeningServer, Thrift}
import com.twitter.util.Future
import example.thrift.SimpleService.{SimpleService as SimpleServiceAlg}
import example.thrift.{SimpleRequest, SimpleResponse, SimpleService}
import munit.CatsEffectSuite

import java.net.{InetAddress, InetSocketAddress}
import scala.concurrent.duration.*

class ThriftClientSpec extends CatsEffectSuite {

  /**
   * A Thrift server listening on an ephemeral loopback port, which reports
   * its stats to `stats` so the tests can watch its open connections.
   */
  private case class StatsReportingServer(server: ListeningServer, stats: InMemoryStatsReceiver) {
    def dest: String = s"127.0.0.1:${server.boundAddress.asInstanceOf[InetSocketAddress].getPort}"

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
          .serveIface(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), echoingService)
      })(s => liftFuture[IO](IO(s.close())))
        .map(StatsReportingServer(_, stats))
    }

  private val serverFixture = ResourceFunFixture(server)

  serverFixture.test("releasing the client Resource closes the Finagle client's connections") { server =>
    for {
      openWhileInUse <- ThriftClient[SimpleServiceAlg][IO](server.dest).use { client =>
        client.makeRequest(SimpleRequest("foo")).timeout(10.seconds) >> server.openConnections
      }
      openAfterRelease <- server.openConnectionsOnceAllClose
    } yield {
      assertEquals(openWhileInUse, 1.0f)
      assertEquals(openAfterRelease, 0.0f)
    }
  }
}
