package com.dwolla.util.async.finagle

import cats.effect.*
import cats.syntax.all.*
import com.dwolla.util.async.twitter.*
import com.twitter.finagle.service.RetryBudget
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.finagle.{ListeningServer, Thrift}
import com.twitter.util.{Future, Time}
import example.thrift.SimpleService.{SimpleService as SimpleServiceAlg}
import example.thrift.{SimpleRequest, SimpleResponse, SimpleService}
import munit.CatsEffectSuite

import java.net.{InetAddress, InetSocketAddress}
import scala.annotation.nowarn
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

  /**
   * Finagle's load balancer picks between the client's hosts at random, so
   * with this many calls it all but certainly sends some of them to the
   * server that refuses connections.
   */
  private val callsSpreadAcrossBothServers = 100

  ResourceFunFixture((server, server).tupled).test("with the default configuration, calls that a closed server refuses are requeued to another server") {
    case (closedServer, openServer) =>
      for {
        _ <- liftFuture[IO](IO(closedServer.server.close(Time.Bottom)))
        replies <- ThriftClient[SimpleServiceAlg][IO](s"${closedServer.dest},${openServer.dest}", ThriftClientConfiguration()).use { client =>
          List.range(0, callsSpreadAcrossBothServers).map(_.toString).traverse { id =>
            client.makeRequest(SimpleRequest(id)).timeout(10.seconds).attempt
          }
        }
      } yield {
        assertEquals(replies.collect { case Left(failure) => failure.toString }, Nil)
      }
  }

  ResourceFunFixture((server, server).tupled).test("with RetryBudget.Empty, calls that a closed server refuses fail instead of being requeued") {
    case (closedServer, openServer) =>
      for {
        _ <- liftFuture[IO](IO(closedServer.server.close(Time.Bottom)))
        replies <- ThriftClient[SimpleServiceAlg][IO](s"${closedServer.dest},${openServer.dest}", ThriftClientConfiguration().withRetryBudget(RetryBudget.Empty)).use { client =>
          List.range(0, callsSpreadAcrossBothServers).map(_.toString).traverse { id =>
            client.makeRequest(SimpleRequest(id)).timeout(10.seconds).attempt
          }
        }
      } yield {
        val failures = replies.collect { case Left(failure) => failure.toString }
        assert(failures.nonEmpty, "no call failed, so the empty budget didn't prevent requeues")
        assert(failures.forall(_.contains("refused")), failures)
      }
  }

  /**
   * The `budget` gauge Finagle's retry module registers for a client,
   * reporting the number of requeues that client may still make. Finagle
   * scopes the client's stats by its label, which defaults to its destination.
   * Finagle builds the retry module, and so registers the gauge, only once
   * the client's destination has been bound, which can happen after the
   * client is created, so this waits for the gauge to appear.
   */
  private def retryBudgetBalance(stats: InMemoryStatsReceiver, dest: String): IO[Float] =
    IO(stats.gauges.get(Seq(dest, "retries", "budget")))
      .flatTap(gauge => IO.sleep(10.millis).whenA(gauge.isEmpty))
      .iterateUntil(_.isDefined)
      .timeoutTo(5.seconds, IO.raiseError(new NoSuchElementException(s"the client reported no retry budget gauge; it reported ${stats.gauges.keys}")))
      .flatMap(gauge => IO(gauge.get.apply()))

  ResourceFunFixture((server, server).tupled).test("clients built from one configuration have independent retry budgets") {
    case (closedServer, openServer) =>
      val sharedConfiguration = ThriftClientConfiguration()
      val busyStats = new InMemoryStatsReceiver
      val idleStats = new InMemoryStatsReceiver
      val dest = s"${closedServer.dest},${openServer.dest}"

      for {
        _ <- liftFuture[IO](IO(closedServer.server.close(Time.Bottom)))
        result <- (
          ThriftClient[SimpleServiceAlg][IO](dest, sharedConfiguration.withStatsReceiver(busyStats)),
          ThriftClient[SimpleServiceAlg][IO](dest, sharedConfiguration.withStatsReceiver(idleStats)),
        ).tupled.use { case (busyClient, _) =>
          for {
            idleBalanceBefore <- retryBudgetBalance(idleStats, dest)
            _ <- List.range(0, callsSpreadAcrossBothServers).map(_.toString).traverse_ { id =>
              busyClient.makeRequest(SimpleRequest(id)).timeout(10.seconds)
            }
            idleBalanceAfter <- retryBudgetBalance(idleStats, dest)
          } yield (idleBalanceBefore, idleBalanceAfter)
        }
      } yield {
        val (idleBalanceBefore, idleBalanceAfter) = result
        assert(busyStats.counters.getOrElse(Seq(dest, "retries", "requeues"), 0L) > 0, "the busy client never requeued, so it never spent its budget")
        assertEquals(idleBalanceAfter, idleBalanceBefore, "the busy client's requeues changed the idle client's budget")
      }
  }

  @nowarn("cat=deprecation")
  private def deprecatedRetryBudget(config: ThriftClientConfiguration): RetryBudget = config.retryBudget

  test("the deprecated retryBudget getter returns the budget set by withRetryBudget") {
    val budget = RetryBudget()

    assert(deprecatedRetryBudget(ThriftClientConfiguration().withRetryBudget(budget)) eq budget)
  }
}
