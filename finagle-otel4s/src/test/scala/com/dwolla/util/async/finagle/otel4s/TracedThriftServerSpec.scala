package com.dwolla.util.async.finagle.otel4s

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.dwolla.util.async.twitter.*
import com.twitter.finagle.Thrift
import com.twitter.finagle.tracing.{Flags, SpanId, Trace, TraceId}
import example.thrift.SimpleService.{SimpleService as SimpleServiceAlg}
import example.thrift.{SimpleRequest, SimpleResponse, SimpleService}
import io.opentelemetry.api.trace.{SpanKind as JSpanKind, StatusCode as JStatusCode}
import io.opentelemetry.extension.trace.propagation.B3Propagator
import io.opentelemetry.sdk.trace.data.SpanData
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.scalacheck.effect.PropF
import org.scalacheck.{Arbitrary, Gen}
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}

import java.net.InetSocketAddress
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters.*

class TracedThriftServerSpec extends CatsEffectSuite with ScalaCheckEffectSuite {
  private implicit val ec: ExecutionContext = munitExecutionContext

  private val failingRequestId = "fail"

  private val loopback: SocketAddress[IpAddress] = SocketAddress(ip"127.0.0.1", port"0")

  /**
   * A Thrift implementation that starts a child span of whatever span is current,
   * so the tests can see that the server span is in scope while the request is handled.
   */
  private def simpleService(implicit T: Tracer[IO]): SimpleServiceAlg[IO] = new SimpleServiceAlg[IO] {
    override def makeRequest(request: SimpleRequest): IO[SimpleResponse] =
      Tracer[IO].span("handler").surround {
        if (request.id == failingRequestId) IO.raiseError(new IllegalStateException(s"request ${request.id} failed"))
        else SimpleResponse(request.id).pure[IO]
      }
  }

  private case class TracedServer(testkit: TracesTestkit[IO],
                             tracer: Tracer[IO],
                             client: SimpleService.MethodPerEndpoint)

  /**
   * Starts a traced server on an ephemeral port and connects a Finagle client to it.
   *
   * @param propagateFinagleTrace whether the client negotiates Finagle's TTwitter protocol upgrade,
   *                              which is how Finagle sends its current `TraceId` to the server
   */
  private def fixture(propagateFinagleTrace: Boolean): Resource[IO, TracedServer] =
    for {
      testkit <- TracesTestkit.inMemory[IO](_.addTextMapPropagators(B3Propagator.injectingMultiHeaders()))
      tracer <- testkit.tracerProvider.get("TracedThriftServerSpec").toResource
      server <- {
        implicit val tp: TracerProvider[IO] = testkit.tracerProvider
        implicit val t: Tracer[IO] = tracer
        // the server is started inside a span so the tests can tell that requests without
        // an incoming trace start root spans rather than inheriting whatever was current at startup
        tracer.span("server-startup").resource.flatMap { res =>
          TracedThriftServer[IO, SimpleServiceAlg](loopback, "foo-service", simpleService).mapK(res.trace)
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
    } yield TracedServer(testkit, tracer, client)

  private def call(client: SimpleService.MethodPerEndpoint,
                   requestId: String,
                   finagleTraceId: Option[TraceId]): IO[SimpleResponse] =
    liftFuture[IO] {
      IO {
        val request = SimpleRequest(requestId)
        finagleTraceId.fold(client.makeRequest(request)) { id =>
          // terminal, so the Finagle client sends exactly this TraceId instead of deriving a child from it
          Trace.letId(id, terminal = true)(client.makeRequest(request))
        }
      }
    }

  private def serverSpans(testkit: TracesTestkit[IO]): IO[List[SpanData]] =
    testkit.finishedSpans.map(_.filter(_.getKind == JSpanKind.SERVER))

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

  fixtureWithFinagleTracePropagation.test("a call produces one Server span named after the algebra and method") { f =>
    for {
      response <- call(f.client, "foo", exampleTraceId.some)
      spans <- serverSpans(f.testkit)
    } yield {
      assertEquals(response.id, "foo")
      assertEquals(spans.map(_.getName), List("SimpleService.makeRequest"))
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
      assertEquals(spans.map(_.getName), List("SimpleService.makeRequest"))
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
}
