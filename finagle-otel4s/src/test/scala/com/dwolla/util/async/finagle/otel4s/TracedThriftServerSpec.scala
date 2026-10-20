package com.dwolla.util.async.finagle.otel4s

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.dwolla.util.async.twitter.*
import com.twitter.finagle.Thrift
import com.twitter.finagle.tracing.{Annotation, Flags, SpanId, Trace, TraceId}
import com.twitter.util.{Future, TimeoutException}
import example.thrift.SimpleService.{SimpleService as SimpleServiceAlg}
import example.thrift.{SimpleRequest, SimpleResponse, SimpleService}
import io.opentelemetry.api.trace.{SpanKind as JSpanKind, StatusCode as JStatusCode}
import io.opentelemetry.sdk.trace.data.SpanData
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.scalacheck.effect.PropF
import org.scalacheck.{Arbitrary, Gen}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit
import org.typelevel.otel4s.trace.{SpanBuilder, Tracer, TracerProvider}

import java.net.InetSocketAddress
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class TracedThriftServerSpec extends CatsEffectSuite with ScalaCheckEffectSuite {
  private implicit val ec: ExecutionContext = munitExecutionContext

  private val failingRequestId = "fail"
  private val neverCompletingRequestId = "never"

  private val loopback: SocketAddress[IpAddress] = SocketAddress(ip"127.0.0.1", port"0")

  /**
   * Latches reporting when the handler of a request that never completes starts running, and when it is canceled.
   */
  private case class NeverCompletingHandler(started: Deferred[IO, Unit],
                                            canceled: Deferred[IO, Unit]) {
    val response: IO[SimpleResponse] = (started.complete(()) >> IO.never[SimpleResponse]).onCancel(canceled.complete(()).void)
  }

  /**
   * A Thrift implementation that starts a child span of whatever span is current,
   * so the tests can see that the server span is in scope while the request is handled.
   */
  private def simpleService(neverCompletingHandler: NeverCompletingHandler)
                           (implicit T: Tracer[IO]): SimpleServiceAlg[IO] = new SimpleServiceAlg[IO] {
    override def makeRequest(request: SimpleRequest): IO[SimpleResponse] =
      Tracer[IO].span("handler").surround {
        if (request.id == failingRequestId) IO.raiseError(new IllegalStateException(s"request ${request.id} failed"))
        else if (request.id == neverCompletingRequestId) neverCompletingHandler.response
        else SimpleResponse(request.id).pure[IO]
      }
  }

  private case class TracedServer(testkit: TracesTestkit[IO],
                                   tracer: Tracer[IO],
                                   client: SimpleService.MethodPerEndpoint,
                                   neverCompletingHandler: NeverCompletingHandler)

  /**
   * Starts a traced server on an ephemeral port and connects a Finagle client to it.
   *
   * @param propagateFinagleTrace whether the client negotiates Finagle's TTwitter protocol upgrade,
   *                              which is how Finagle sends its current `TraceId` to the server
   */
  private def fixture(propagateFinagleTrace: Boolean): Resource[IO, TracedServer] =
    fixture(propagateFinagleTrace, identity[SpanBuilder[IO]])

  private def customizedFixture(customizeSpan: SpanBuilder[IO] => SpanBuilder[IO]): Resource[IO, TracedServer] =
    fixture(propagateFinagleTrace = true, customizeSpan)

  private def fixture(propagateFinagleTrace: Boolean,
                      customizeSpan: SpanBuilder[IO] => SpanBuilder[IO]): Resource[IO, TracedServer] =
    for {
      testkit <- TracesTestkit.inMemory[IO]()
      tracer <- testkit.tracerProvider.get("TracedThriftServerSpec").toResource
      neverCompletingHandler <- (Deferred[IO, Unit], Deferred[IO, Unit]).mapN(NeverCompletingHandler(_, _)).toResource
      server <- {
        implicit val tp: TracerProvider[IO] = testkit.tracerProvider
        implicit val t: Tracer[IO] = tracer
        // the server is started inside a span so the tests can tell that requests without
        // an incoming trace start root spans rather than inheriting whatever was current at startup
        tracer.span("server-startup").resource.flatMap { res =>
          TracedThriftServer[IO, SimpleServiceAlg](loopback, "foo-service", simpleService(neverCompletingHandler), customizeSpan).mapK(res.trace)
        }
      }
      client <- Resource.make {
        IO {
          val clientBuilder = if (propagateFinagleTrace) Thrift.client else Thrift.client.withNoAttemptTTwitterUpgrade
          val port = server.boundAddress.asInstanceOf[InetSocketAddress].getPort
          clientBuilder.build[SimpleService.MethodPerEndpoint](s"127.0.0.1:$port")
        }
      }(c => liftFuture[IO](IO(c.asClosable.close())))
      _ <- testkit.resetSpans.toResource
    } yield TracedServer(testkit, tracer, client, neverCompletingHandler)

  private def startCall(client: SimpleService.MethodPerEndpoint,
                        requestId: String,
                        finagleTraceId: Option[TraceId]): IO[Future[SimpleResponse]] =
    IO {
      val request = SimpleRequest(requestId)
      finagleTraceId.fold(client.makeRequest(request)) { id =>
        // terminal, so the Finagle client sends exactly this TraceId instead of deriving a child from it
        Trace.letId(id, terminal = true)(client.makeRequest(request))
      }
    }

  private def call(client: SimpleService.MethodPerEndpoint,
                   requestId: String,
                   finagleTraceId: Option[TraceId]): IO[SimpleResponse] =
    liftFuture[IO](startCall(client, requestId, finagleTraceId))

  private def stringAttribute(span: SpanData, key: String): Option[String] =
    Option(span.getAttributes.get(io.opentelemetry.api.common.AttributeKey.stringKey(key)))

  private def serverSpans(testkit: TracesTestkit[IO]): IO[List[SpanData]] =
    testkit.finishedSpans.map(_.filter(_.getKind == JSpanKind.SERVER))

  /**
   * Polls until the server span has been exported, for tests where it ends after the client's call returns.
   */
  private def awaitServerSpans(testkit: TracesTestkit[IO]): IO[List[SpanData]] =
    serverSpans(testkit)
      .flatTap(spans => IO.sleep(10.millis).whenA(spans.isEmpty))
      .iterateUntil(_.nonEmpty)
      .timeout(10.seconds)

  private def handlerSpans(testkit: TracesTestkit[IO]): IO[List[SpanData]] =
    testkit.finishedSpans.map(_.filter(_.getName == "handler"))

  private val genNonZeroLong: Gen[Long] = Gen.long.suchThat(_ != 0L)

  /**
   * A sampled Finagle `TraceId`, as a Finagle client would send it. Both 64-bit
   * and 128-bit trace IDs are generated, with and without a parent span.
   */
  private implicit val arbSampledTraceId: Arbitrary[TraceId] = Arbitrary {
    for {
      traceIdLow <- genNonZeroLong
      traceIdHigh <- Gen.option(genNonZeroLong)
      parentId <- Gen.option(genNonZeroLong)
      spanId <- genNonZeroLong
    } yield TraceId(
      _traceId = SpanId(traceIdLow).some,
      _parentId = parentId.map(SpanId(_)),
      spanId = SpanId(spanId),
      _sampled = true.some,
      flags = Flags(),
      traceIdHigh = traceIdHigh.map(SpanId(_)),
    )
  }

  private val exampleTraceId: TraceId =
    TraceId(
      _traceId = SpanId(0x463ac35c9f6413adL).some,
      _parentId = None,
      spanId = SpanId(0x72485a3953bb6124L),
      _sampled = true.some,
      flags = Flags(),
    )

  private def expectedOtelTraceId(t: TraceId): String =
    t.traceIdHigh.fold("0" * 16)(_.toString) + t.traceId.toString

  private val fixtureWithFinagleTracePropagation = ResourceFunFixture(fixture(propagateFinagleTrace = true))
  private val fixtureWithoutFinagleTracePropagation = ResourceFunFixture(fixture(propagateFinagleTrace = false))

  fixtureWithFinagleTracePropagation.test("a call produces one Server span named {service}/{method}") { f =>
    for {
      response <- call(f.client, "foo", exampleTraceId.some)
      spans <- serverSpans(f.testkit)
    } yield {
      assertEquals(response.id, "foo")
      assertEquals(spans.map(_.getName), List("SimpleService/makeRequest"))
    }
  }

  fixtureWithFinagleTracePropagation.test("the server span carries the Thrift RPC system and fully-qualified method") { f =>
    for {
      _ <- call(f.client, "foo", exampleTraceId.some)
      spans <- serverSpans(f.testkit)
    } yield {
      assertEquals(spans.size, 1)
      assertEquals(stringAttribute(spans.head, "rpc.system.name"), Some("thrift"))
      assertEquals(stringAttribute(spans.head, "rpc.method"), Some("SimpleService/makeRequest"))
      assertEquals(stringAttribute(spans.head, "rpc.system"), None)
      assertEquals(stringAttribute(spans.head, "rpc.service"), None)
    }
  }

  test("customizeSpan runs after the RPC attributes are set, so it can override them") {
    val overridden = "custom/method"
    val customize: SpanBuilder[IO] => SpanBuilder[IO] = _.addAttribute(Attribute("rpc.method", overridden))

    customizedFixture(customize).use { f =>
      for {
        _ <- call(f.client, "foo", exampleTraceId.some)
        spans <- serverSpans(f.testkit)
      } yield {
        assertEquals(spans.size, 1)
        assertEquals(stringAttribute(spans.head, "rpc.method"), Some(overridden))
        assertEquals(stringAttribute(spans.head, "rpc.system.name"), Some("thrift"))
      }
    }
  }

  fixtureWithFinagleTracePropagation.test("the server span joins the trace identified by the incoming Finagle TraceId") { f =>
    PropF.forAllF { (incoming: TraceId) =>
      for {
        _ <- f.testkit.resetSpans
        _ <- call(f.client, "foo", incoming.some)
        spans <- serverSpans(f.testkit)
      } yield {
        assertEquals(spans.size, 1)
        val span = spans.head
        assertEquals(span.getTraceId, expectedOtelTraceId(incoming))
        assertEquals(span.getParentSpanContext.getSpanId, incoming.spanId.toString)
        assert(span.getParentSpanContext.isRemote, "the parent should be the remote span that called this server")
      }
    }
  }

  fixtureWithFinagleTracePropagation.test("an incoming Finagle TraceId with no sampling decision is continued as sampled") { f =>
    val undecided = exampleTraceId.copy(_sampled = None)

    for {
      _ <- call(f.client, "foo", undecided.some)
      spans <- serverSpans(f.testkit)
    } yield {
      assertEquals(spans.map(_.getTraceId), List(expectedOtelTraceId(undecided)))
      assert(spans.forall(_.getSpanContext.isSampled), "the server span should be sampled")
    }
  }

  fixtureWithFinagleTracePropagation.test("an incoming Finagle TraceId explicitly marked not sampled is continued as not sampled") { f =>
    val unsampled = exampleTraceId.copy(_sampled = false.some)

    for {
      response <- call(f.client, "foo", unsampled.some)
      spans <- serverSpans(f.testkit)
    } yield {
      assertEquals(response.id, "foo")
      assertEquals(spans, Nil)
    }
  }

  fixtureWithFinagleTracePropagation.test("Finagle's own tracer, registered through LoadService, records nothing on the server") { f =>
    for {
      _ <- IO(RecordingFinagleTracer.reset())
      _ <- call(f.client, "foo", exampleTraceId.some)
      serverAnnotations = RecordingFinagleTracer.records().map(_.annotation).filter {
        case Annotation.ServerRecv | Annotation.ServerSend => true
        case _ => false
      }
    } yield {
      assert(RecordingFinagleTracer.loadedByFinagle, "the test Tracer should have been instantiated through LoadService")
      assertEquals(serverAnnotations, Nil)
    }
  }

  fixtureWithFinagleTracePropagation.test("the server span is current while the Thrift implementation runs") { f =>
    for {
      _ <- call(f.client, "foo", exampleTraceId.some)
      server <- serverSpans(f.testkit)
      handler <- handlerSpans(f.testkit)
    } yield {
      assertEquals(handler.map(_.getParentSpanId), server.map(_.getSpanId))
      assertEquals(handler.map(_.getTraceId), server.map(_.getTraceId))
    }
  }

  fixtureWithoutFinagleTracePropagation.test("without an incoming Finagle TraceId, the server span is a root span") { f =>
    for {
      _ <- call(f.client, "foo", None)
      spans <- serverSpans(f.testkit)
    } yield {
      assertEquals(spans.map(_.getName), List("SimpleService/makeRequest"))
      assert(!spans.head.getParentSpanContext.isValid, s"expected a root span, but its parent was ${spans.head.getParentSpanContext}")
    }
  }

  fixtureWithFinagleTracePropagation.test("a failing call records the exception and an error status on the server span") { f =>
    for {
      result <- call(f.client, failingRequestId, exampleTraceId.some).attempt
      spans <- serverSpans(f.testkit)
    } yield {
      assert(result.isLeft, "the client should see the call fail")
      assertEquals(spans.size, 1)
      val span = spans.head
      assertEquals(span.getStatus.getStatusCode, JStatusCode.ERROR)
      val exceptionMessages = span.getEvents.asScala.toList
        .filter(_.getName == "exception")
        .flatMap(e => Option(e.getAttributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("exception.message"))))
      assertEquals(exceptionMessages, List(s"request $failingRequestId failed"))
    }
  }

  fixtureWithFinagleTracePropagation.test("a client giving up on a call cancels the Thrift implementation handling it and ends the server span") { f =>
    for {
      response <- startCall(f.client, neverCompletingRequestId, exampleTraceId.some)
      _ <- f.neverCompletingHandler.started.get.timeout(10.seconds)
      // what Finagle's own request timeout raises; the client closes the connection, so the server interrupts the request
      _ <- IO(response.raise(new TimeoutException("the client gave up waiting")))
      _ <- f.neverCompletingHandler.canceled.get.timeout(10.seconds)
      spans <- awaitServerSpans(f.testkit)
    } yield {
      assertEquals(spans.map(_.getName), List("SimpleService/makeRequest"))
      assertEquals(spans.map(s => (s.getStatus.getStatusCode, s.getStatus.getDescription)), List((JStatusCode.ERROR, "canceled")))
    }
  }
}
