package com.dwolla.util.async.finagle

import cats.syntax.all._
import com.twitter.finagle.tracing.{Flags, SpanId, TraceId, TraceId128}
import natchez.Kernel

// TODO could this use OpenTelemetry's TextMapPropagator instead of doing it ourselves?
object ZipkinKernel {

  // TODO this propagates the headers in the B3 multi-header format, but maybe it should convert to OpenTelemetry / W3C's tracecontext header?
  def asKernel(t: TraceId): Kernel = Kernel {
    val headers: List[(String, String)] =
      List(
        "X-B3-TraceId" -> (t.traceIdHigh.map(_.toString).orEmpty + t.traceId.toString()),
        "X-B3-SpanId" -> t.spanId.toString(),
      ) ++
        t._parentId.map(_.toString).map("X-B3-ParentSpanId" -> _).toList ++
        t.sampled.ifM(Option("X-B3-Sampled" -> "1"), None).toList

    headers.toMap
  }

  def asTraceId(kernel: Kernel): Option[TraceId] = {
    val headers = kernel.toHeaders

    headers.get("X-B3-SpanId")
      .flatMap(SpanId.fromString)
      .map {
        val traceId = headers.get("X-B3-TraceId").map(TraceId128(_))
        val parentId = headers.get("X-B3-ParentSpanId").flatMap(SpanId.fromString)
        val sampled = headers.get("X-B3-Sampled").collect {
          case "1" => true
        }

        TraceId(
          _traceId = traceId.flatMap(_.low),
          _parentId = parentId,
          _,
          _sampled = sampled,
          flags = Flags(),
          traceIdHigh = traceId.flatMap(_.high),
        )
      }
  }
}
