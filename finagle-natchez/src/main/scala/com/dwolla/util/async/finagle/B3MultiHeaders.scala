package com.dwolla.util.async.finagle

import cats.syntax.all.*
import com.twitter.finagle.tracing.TraceId

/**
 * The B3 multi-header names, and the rendering of a Finagle `TraceId` as
 * those headers, that `ZipkinKernel` uses to convert between a Finagle
 * `TraceId` and a natchez `Kernel`.
 *
 * A `TraceId` with no sampling decision is rendered as sampled. B3 says an
 * absent sampling decision defers it to the receiver, but OpenTelemetry's B3
 * propagators read an absent `X-B3-Sampled` as "not sampled", so a parent-based
 * sampler would drop every span continued from an undecided caller.
 */
private[finagle] object B3MultiHeaders {
  val TraceIdHeader = "X-B3-TraceId"
  val SpanIdHeader = "X-B3-SpanId"
  val ParentSpanIdHeader = "X-B3-ParentSpanId"
  val SampledHeader = "X-B3-Sampled"

  def fromTraceId(t: TraceId): Map[String, String] =
    (
      List(
        TraceIdHeader -> (t.traceIdHigh.map(_.toString).orEmpty + t.traceId.toString()),
        SpanIdHeader -> t.spanId.toString(),
      ) ++
        t._parentId.map(_.toString).map(ParentSpanIdHeader -> _).toList ++
        List(samplingHeader(t))
    ).toMap

  private def samplingHeader(t: TraceId): (String, String) =
    SampledHeader -> (if (t.sampled.contains(false)) "0" else "1")
}
