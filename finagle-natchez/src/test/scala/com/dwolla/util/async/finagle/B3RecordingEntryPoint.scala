package com.dwolla.util.async.finagle

import cats.data.Chain
import cats.effect.*
import cats.syntax.all.*
import natchez.*
import org.typelevel.ci.*

import java.net.URI

/**
 * A span that a `B3RecordingEntryPoint` created and that has ended.
 *
 * @param traceId      the trace ID, as the hex string in its `X-B3-TraceId` header
 * @param spanId       the span ID, as the hex string in its `X-B3-SpanId` header
 * @param parentSpanId the parent's span ID, if the span has a parent
 * @param errors       the errors attached to the span
 * @param exitCase     how the `Resource` managing the span ended
 */
final case class FinishedSpan(name: String,
                              kind: Span.SpanKind,
                              traceId: String,
                              spanId: String,
                              parentSpanId: Option[String],
                              sampled: Boolean,
                              fields: Map[String, TraceValue],
                              errors: List[Throwable],
                              exitCase: Resource.ExitCase)

/**
 * A natchez `EntryPoint` for tests that records every span when it ends. Each span's kernel
 * carries that span's own IDs as B3 multi-headers, the way a B3-propagating tracing backend
 * sends them, so a test can see exactly which span's ID was sent to a remote service.
 *
 * Span IDs are assigned in sequence starting at `firstSpanId`, so they're deterministic.
 * A root span gets a 64-bit trace ID equal to its span ID, and is sampled.
 */
class B3RecordingEntryPoint private(spanIds: Ref[IO, Long],
                                    finished: Ref[IO, Chain[FinishedSpan]]) extends EntryPoint[IO] {
  import B3RecordingEntryPoint.*

  /** The spans that have ended so far, in the order they ended. */
  def finishedSpans: IO[List[FinishedSpan]] = finished.get.map(_.toList)

  /** Forgets the spans that have ended so far. */
  def reset: IO[Unit] = finished.set(Chain.empty)

  override def root(name: String, options: Span.Options): Resource[IO, Span[IO]] =
    Resource.eval(nextSpanId).flatMap { spanId =>
      newSpan(name, options, traceId = spanId, parentSpanId = None, sampled = true, spanId)
    }

  override def continue(name: String, kernel: Kernel, options: Span.Options): Resource[IO, Span[IO]] =
    Resource.eval(remoteParent(kernel).liftTo[IO](new NoSuchElementException(s"no B3 trace in $kernel")))
      .flatMap(parent => childOf(parent, name, options))

  override def continueOrElseRoot(name: String, kernel: Kernel, options: Span.Options): Resource[IO, Span[IO]] =
    remoteParent(kernel).fold(root(name, options))(childOf(_, name, options))

  private def nextSpanId: IO[String] =
    spanIds.getAndUpdate(_ + 1).map(hex)

  private def childOf(parent: Parent, name: String, options: Span.Options): Resource[IO, Span[IO]] =
    Resource.eval(nextSpanId).flatMap { spanId =>
      newSpan(name, options, parent.traceId, parent.spanId.some, parent.sampled, spanId)
    }

  private def newSpan(name: String,
                      options: Span.Options,
                      traceId: String,
                      parentSpanId: Option[String],
                      sampled: Boolean,
                      spanId: String): Resource[IO, Span[IO]] = {
    val acquire = (Ref[IO].of(Map.empty[String, TraceValue]), Ref[IO].of(List.empty[Throwable])).mapN {
      new RecordingSpan(name, options.spanKind, traceId, spanId, parentSpanId, sampled, _, _)
    }

    Resource.makeCase(acquire) { (span, exitCase) =>
      (span.fields.get, span.errors.get).flatMapN { (fields, errors) =>
        finished.update(_.append(FinishedSpan(name, options.spanKind, traceId, spanId, parentSpanId, sampled, fields, errors.reverse, exitCase)))
      }
    }.widen
  }

  private class RecordingSpan(name: String,
                              kind: Span.SpanKind,
                              traceIdHex: String,
                              spanIdHex: String,
                              parentSpanIdHex: Option[String],
                              sampled: Boolean,
                              val fields: Ref[IO, Map[String, TraceValue]],
                              val errors: Ref[IO, List[Throwable]]) extends Span[IO] {
    override def put(fields: (String, TraceValue)*): IO[Unit] = this.fields.update(_ ++ fields)
    override def log(fields: (String, TraceValue)*): IO[Unit] = IO.unit
    override def log(event: String): IO[Unit] = IO.unit
    override def attachError(err: Throwable, fields: (String, TraceValue)*): IO[Unit] =
      errors.update(err :: _) >> this.fields.update(_ ++ fields)

    override def kernel: IO[Kernel] =
      Kernel(
        Map[CIString, String](
          ci"X-B3-TraceId" -> traceIdHex,
          ci"X-B3-SpanId" -> spanIdHex,
          ci"X-B3-Sampled" -> (if (sampled) "1" else "0"),
        ) ++ parentSpanIdHex.map(ci"X-B3-ParentSpanId" -> _)
      ).pure[IO]

    override def span(name: String, options: Span.Options): Resource[IO, Span[IO]] =
      childOf(Parent(traceIdHex, spanIdHex, sampled), name, options)

    override def traceId: IO[Option[String]] = traceIdHex.some.pure[IO]
    override def spanId: IO[Option[String]] = spanIdHex.some.pure[IO]
    override def traceUri: IO[Option[URI]] = none[URI].pure[IO]

    override def toString: String = s"RecordingSpan($name, $kind, $traceIdHex, $spanIdHex)"
  }
}

object B3RecordingEntryPoint {
  def apply(firstSpanId: Long): IO[B3RecordingEntryPoint] =
    (Ref[IO].of(firstSpanId), Ref[IO].of(Chain.empty[FinishedSpan])).mapN(new B3RecordingEntryPoint(_, _))

  def hex(l: Long): String = f"$l%016x"

  /** A B3 kernel identifying a span in another process, such as the caller of the code under test. */
  def remoteKernel(traceId: String, spanId: Long, sampled: Boolean): Kernel =
    Kernel(Map(
      ci"X-B3-TraceId" -> traceId,
      ci"X-B3-SpanId" -> hex(spanId),
      ci"X-B3-Sampled" -> (if (sampled) "1" else "0"),
    ))

  private case class Parent(traceId: String, spanId: String, sampled: Boolean)

  private def remoteParent(kernel: Kernel): Option[Parent] = {
    val headers = kernel.toHeaders
    (headers.get(ci"X-B3-TraceId"), headers.get(ci"X-B3-SpanId")).mapN { (traceId, spanId) =>
      Parent(traceId, spanId, !headers.get(ci"X-B3-Sampled").contains("0"))
    }
  }
}
