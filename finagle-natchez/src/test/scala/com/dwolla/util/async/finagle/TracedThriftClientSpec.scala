package com.dwolla.util.async.finagle

import cats.effect.*
import cats.effect.std.Dispatcher
import cats.mtl.Local
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.dwolla.util.async.finagle.B3RecordingEntryPoint.{hex, remoteKernel}
import com.dwolla.util.async.twitter.*
import com.dwolla.util.async.twitter.CancelledViaCatsEffect
import com.twitter.finagle.{ListeningServer, Thrift}
import com.twitter.finagle.tracing.{Annotation, NullTracer, Record, TraceId, Tracer, Trace as FinagleTrace}
import com.twitter.util.Future
import example.thrift.SimpleService.{SimpleService as SimpleServiceAlg}
import example.thrift.{SimpleRequest, SimpleResponse, SimpleService}
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import natchez.{Span, Trace, TraceValue}
import org.scalacheck.effect.PropF
import org.scalacheck.{Arbitrary, Gen}

import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.{Handler, Level, LogRecord, Logger}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

class TracedThriftClientSpec extends CatsEffectSuite with ScalaCheckEffectSuite {
  private implicit val ec: ExecutionContext = munitExecutionContext

  private val failingRequestId = "fail"
  private val neverCompletingRequestId = "never"

  private val loopback: SocketAddress[IpAddress] = SocketAddress(ip"127.0.0.1", port"0")

  /**
   * The Finagle `TraceId` a server received with a request, as hex strings, so it
   * can travel back to the test in the response.
   */
  private case class ReceivedTraceId(traceId: String,
                                     spanId: String,
                                     parentSpanId: Option[String],
                                     sampled: Option[Boolean]) {
    def encode: String = List(traceId, spanId, parentSpanId.orEmpty, sampled.fold("")(_.toString)).mkString(",")
  }

  private object ReceivedTraceId {
    def apply(t: TraceId): ReceivedTraceId =
      ReceivedTraceId(t.traceIdHigh.map(_.toString).orEmpty + t.traceId.toString, t.spanId.toString, t._parentId.map(_.toString), t.sampled)

    def decode(s: String): ReceivedTraceId =
      s.split(",", -1).toList match {
        case List(traceId, spanId, parentSpanId, sampled) =>
          ReceivedTraceId(traceId, spanId, parentSpanId.some.filter(_.nonEmpty), sampled.some.filter(_.nonEmpty).map(_.toBoolean))
        case _ => throw new IllegalArgumentException(s"not an encoded ReceivedTraceId: $s")
      }
  }

  /**
   * A Thrift server that responds with the Finagle `TraceId` it received with the request,
   * so the tests can see exactly what the client sent on the wire. Finagle's own tracing is
   * disabled on it, so no `Tracer` registered through `LoadService` reports anything.
   */
  private def traceIdEchoingServer(dispatcher: Dispatcher[IO],
                                   neverCompletingRequestStarted: Deferred[IO, Unit]): SimpleService.MethodPerEndpoint =
    new SimpleService.MethodPerEndpoint {
      override def makeRequest(request: SimpleRequest): Future[SimpleResponse] = {
        // Finagle's TraceId is a Local-scoped context, so read it here on the request thread
        val received = FinagleTrace.idOption.map(ReceivedTraceId(_))

        if (request.id == failingRequestId) Future.exception(new IllegalStateException(s"request ${request.id} failed"))
        else if (request.id == neverCompletingRequestId) dispatcher.unsafeToTwitterFuture(neverCompletingRequestStarted.complete(()) >> IO.never[SimpleResponse])
        else Future.value(SimpleResponse(received.fold("")(_.encode)))
      }
    }

  private case class ClientServerFixture(entryPoint: B3RecordingEntryPoint,
                                         trace: Trace[IO],
                                         dest: String,
                                         neverCompletingRequestStarted: Deferred[IO, Unit]) {
    def tracedClient(config: ThriftClientConfiguration = clientConfig,
                     dest: String = dest): Resource[IO, SimpleServiceAlg[IO]] = {
      implicit val T: Trace[IO] = trace
      TracedThriftClient[SimpleServiceAlg][IO, IO](dest, config)
    }

    def clientSpans: IO[List[FinishedSpan]] =
      entryPoint.finishedSpans.map(_.filter(_.kind == Span.SpanKind.Client))
  }

  private val clientConfig: ThriftClientConfiguration = ThriftClientConfiguration().withTracer(NullTracer)

  /**
   * Starts a Thrift server that echoes the `TraceId` it receives, on an ephemeral port. The
   * `Trace[IO]` makes each top-level span a root span (or a continuation, if given a parent
   * kernel) created by the recording entry point.
   */
  private val fixture: Resource[IO, ClientServerFixture] =
    for {
      dispatcher <- Dispatcher.parallel[IO]
      neverCompletingRequestStarted <- Deferred[IO, Unit].toResource
      server <- Resource.make(IO {
        Thrift.server
          .withLabel("foo-service")
          .withTracer(NullTracer)
          .serveIface(loopback.toInetSocketAddress, traceIdEchoingServer(dispatcher, neverCompletingRequestStarted))
      })(s => liftFuture[IO](IO(s.close())))
      entryPoint <- B3RecordingEntryPoint(firstSpanId = 0x1000L).toResource
      trace <- Trace.ioTraceForEntryPoint(entryPoint).toResource
    } yield ClientServerFixture(entryPoint, trace, s"127.0.0.1:${port(server)}", neverCompletingRequestStarted)

  private def port(server: ListeningServer): Int =
    server.boundAddress.asInstanceOf[InetSocketAddress].getPort

  private val tracedThriftClientFixture = ResourceFunFixture(fixture)
  private val fixtureWithTracedClient = ResourceFunFixture(fixture.mproduct(_.tracedClient()))

  private def makeRequest(client: SimpleServiceAlg[IO], requestId: String = "foo"): IO[ReceivedTraceId] =
    client.makeRequest(SimpleRequest(requestId)).map(r => ReceivedTraceId.decode(r.id)).timeout(10.seconds)

  /**
   * The span of the remote caller that the ambient span continues.
   */
  private case class RemoteParent(traceId: String, spanId: Long, sampled: Boolean) {
    def options: Span.Options = Span.Options.parentKernel(remoteKernel(traceId, spanId, sampled))
  }

  private val genNonZeroLong: Gen[Long] = Gen.long.suchThat(_ != 0L)

  /**
   * Remote parents with 64-bit and 128-bit trace IDs, sampled or not.
   */
  private implicit val arbRemoteParent: Arbitrary[RemoteParent] = Arbitrary {
    for {
      traceIdHigh <- Gen.option(genNonZeroLong)
      traceIdLow <- genNonZeroLong
      spanId <- genNonZeroLong
      sampled <- Arbitrary.arbitrary[Boolean]
    } yield RemoteParent(traceIdHigh.map(hex).orEmpty + hex(traceIdLow), spanId, sampled)
  }

  private val sampledRemoteParent: RemoteParent = RemoteParent("463ac35c9f6413ad48485a3953bb6124", 0x72485a3953bb6124L, sampled = true)

  private def inAmbientSpan[A](f: ClientServerFixture, remoteParent: RemoteParent)(fa: IO[A]): IO[A] =
    f.trace.span("ambient", remoteParent.options)(fa)

  tracedThriftClientFixture.test("each call is wrapped in a Client span named {service}/{method}, with the Thrift RPC attributes") { f =>
    f.tracedClient().use { client =>
      for {
        _ <- inAmbientSpan(f, sampledRemoteParent)(makeRequest(client))
        spans <- f.clientSpans
      } yield {
        assertEquals(spans.map(_.name), List("SimpleService/makeRequest"))
        assertEquals(spans.map(_.fields), List(Map[String, TraceValue](
          "rpc.system.name" -> "thrift",
          "rpc.method" -> "SimpleService/makeRequest",
        )))
      }
    }
  }

  fixtureWithTracedClient.test("the client span is a child of the ambient span, and its IDs and sampling decision are what the server receives") { case (f, client) =>
    PropF.forAllF { (remoteParent: RemoteParent) =>
      for {
        _ <- f.entryPoint.reset
        received <- inAmbientSpan(f, remoteParent)(makeRequest(client))
        spans <- f.entryPoint.finishedSpans
      } yield {
        val ambient = spans.filter(_.name == "ambient")
        val clientSpans = spans.filter(_.kind == Span.SpanKind.Client)
        assertEquals(ambient.size, 1)
        assertEquals(clientSpans.size, 1)

        val clientSpan = clientSpans.head
        assertEquals(clientSpan.traceId, remoteParent.traceId)
        assertEquals(clientSpan.parentSpanId, ambient.head.spanId.some)
        assertEquals(received, ReceivedTraceId(clientSpan.traceId, clientSpan.spanId, clientSpan.parentSpanId, clientSpan.sampled.some))
        assertEquals(received.sampled, remoteParent.sampled.some)
      }
    }
  }

  tracedThriftClientFixture.test("with no ambient span, the client span starts a new trace, and its IDs are what the server receives") { f =>
    f.tracedClient().use { client =>
      for {
        received <- makeRequest(client)
        spans <- f.entryPoint.finishedSpans
      } yield {
        assertEquals(spans.map(s => (s.name, s.kind, s.parentSpanId)), List(("SimpleService/makeRequest", Span.SpanKind.Client, None)))
        assertEquals(received, ReceivedTraceId(spans.head.traceId, spans.head.spanId, None, true.some))
      }
    }
  }

  tracedThriftClientFixture.test("a failing call attaches the error to the client span") { f =>
    f.tracedClient().use { client =>
      for {
        result <- inAmbientSpan(f, sampledRemoteParent)(makeRequest(client, failingRequestId)).attempt
        spans <- f.clientSpans
      } yield {
        assert(result.isLeft, "the call should fail")
        assertEquals(spans.size, 1)
        assertEquals(spans.head.errors, result.swap.toList)
        assert(spans.head.exitCase.isInstanceOf[Resource.ExitCase.Errored], s"expected the span to end with the error, but it ended with ${spans.head.exitCase}")
      }
    }
  }

  tracedThriftClientFixture.test("canceling a call ends the client span as canceled") { f =>
    (f.tracedClient(), FinagleMonitorLog.capture).tupled.use { case (client, monitorLog) =>
      for {
        call <- inAmbientSpan(f, sampledRemoteParent)(makeRequest(client, neverCompletingRequestId)).start
        _ <- f.neverCompletingRequestStarted.get.timeout(10.seconds)
        _ <- call.cancel.timeout(10.seconds)
        outcome <- call.join
        spans <- f.clientSpans
        // Finagle's client reports the interrupted request to its default monitor, which logs it
        logged <- IO(monitorLog.records()).flatTap(r => IO.sleep(10.millis).whenA(r.isEmpty)).iterateUntil(_.nonEmpty).timeout(10.seconds)
      } yield {
        assert(outcome.isCanceled, s"expected the call to be canceled, but it was $outcome")
        assertEquals(spans.map(s => (s.name, s.exitCase)), List(("SimpleService/makeRequest", Resource.ExitCase.Canceled)))
        assertEquals(logged.map(r => (r.getLevel, r.getThrown)), List((Level.WARNING, CancelledViaCatsEffect)))
      }
    }
  }

  tracedThriftClientFixture.test("the configured Finagle tracer still records the call, under the client span's IDs") { f =>
    val finagleTracer = new RecordingFinagleTracer

    f.tracedClient(clientConfig.withTracer(finagleTracer)).use { client =>
      for {
        received <- inAmbientSpan(f, sampledRemoteParent)(makeRequest(client))
        spans <- f.clientSpans
      } yield {
        val clientSendSpanIds = finagleTracer.records().collect {
          case Record(id, _, Annotation.ClientSend, _) => id.spanId.toString
        }
        assertEquals(clientSendSpanIds, spans.map(_.spanId))
        assertEquals(received.spanId, spans.head.spanId)
      }
    }
  }

  tracedThriftClientFixture.test("a natchez TracedThriftServer's span is a child of the client span") { f =>
    val service: SimpleServiceAlg[IO] = new SimpleServiceAlg[IO] {
      override def makeRequest(request: SimpleRequest): IO[SimpleResponse] = SimpleResponse(request.id).pure[IO]
    }

    val tracedServer: Resource[IO, ListeningServer] =
      IOLocal(Span.noop[IO]).toResource.flatMap { currentSpan =>
        implicit val local: Local[IO, Span[IO]] = currentSpan.asLocal
        TracedThriftServer[IO, SimpleServiceAlg](loopback, "bar-service", service, f.entryPoint)
      }

    tracedServer.flatMap(server => f.tracedClient(dest = s"127.0.0.1:${port(server)}")).use { client =>
      for {
        _ <- inAmbientSpan(f, sampledRemoteParent)(client.makeRequest(SimpleRequest("foo")).timeout(10.seconds))
        spans <- f.entryPoint.finishedSpans
      } yield {
        val clientSpans = spans.filter(_.kind == Span.SpanKind.Client)
        val serverSpans = spans.filter(_.kind == Span.SpanKind.Server)
        assertEquals(clientSpans.size, 1)
        assertEquals(serverSpans.map(s => (s.traceId, s.parentSpanId)), clientSpans.map(s => (s.traceId, s.spanId.some)))
      }
    }
  }

  tracedThriftClientFixture.test("when the ambient Trace has no kernel, each call is sent with a new Finagle trace, as before") { f =>
    implicit val noop: Trace[IO] = Trace.Implicits.noop[IO]

    TracedThriftClient[SimpleServiceAlg][IO, IO](f.dest, clientConfig).use { client =>
      for {
        first <- makeRequest(client)
        second <- makeRequest(client)
      } yield {
        assertNotEquals(first.spanId, second.spanId)
        assertNotEquals(first.traceId, second.traceId)
        assertEquals((first.parentSpanId, second.parentSpanId), (None, None))
      }
    }
  }

  tracedThriftClientFixture.test("the untraced ThriftClient still sends each call with a new Finagle trace") { f =>
    ThriftClient[SimpleServiceAlg][IO](f.dest, clientConfig).use { client =>
      for {
        first <- makeRequest(client)
        second <- makeRequest(client)
        spans <- f.entryPoint.finishedSpans
      } yield {
        assertNotEquals(first.spanId, second.spanId)
        assertNotEquals(first.traceId, second.traceId)
        assertEquals((first.parentSpanId, second.parentSpanId), (None, None))
        assertEquals(spans, Nil)
      }
    }
  }
}

/**
 * Records what Finagle's default `Monitor` logs, instead of letting it reach the console.
 */
class FinagleMonitorLog extends Handler {
  private val recorded = new ConcurrentLinkedQueue[LogRecord]()

  override def publish(record: LogRecord): Unit = {
    recorded.add(record)
    ()
  }

  override def flush(): Unit = ()
  override def close(): Unit = ()

  def records(): List[LogRecord] = recorded.toArray(Array.empty[LogRecord]).toList
}

object FinagleMonitorLog {
  // the logger used by com.twitter.finagle.util.DefaultMonitor, which is private to Finagle
  private val loggerName = "com.twitter.finagle.util.DefaultMonitor"

  def capture: Resource[IO, FinagleMonitorLog] =
    Resource.make {
      IO {
        val logger = Logger.getLogger(loggerName)
        val handler = new FinagleMonitorLog
        logger.addHandler(handler)
        logger.setUseParentHandlers(false)
        (logger, handler)
      }
    } { case (logger, handler) =>
      IO {
        logger.removeHandler(handler)
        logger.setUseParentHandlers(true)
      }
    }.map(_._2)
}

/**
 * A Finagle `Tracer` that records everything reported to it.
 */
class RecordingFinagleTracer extends Tracer {
  private val recorded = new ConcurrentLinkedQueue[Record]()

  override def record(record: Record): Unit = {
    recorded.add(record)
    ()
  }

  override def sampleTrace(traceId: TraceId): Option[Boolean] = None

  def records(): List[Record] = recorded.toArray(Array.empty[Record]).toList
}
