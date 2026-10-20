package com.dwolla.util.async.finagle

import cats.syntax.all.*
import com.twitter.finagle.tracing.TraceId

/**
 * The B3 multi-header names, and the rendering of a Finagle `TraceId` as
 * those headers, that `ZipkinKernel` uses to convert between a Finagle
 * `TraceId` and a natchez `Kernel`.
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
        t.sampled.ifM(Option(SampledHeader -> "1"), None).toList
    ).toMap
}
