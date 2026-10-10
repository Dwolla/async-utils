package com.dwolla.util.async.finagle

import com.twitter.finagle.tracing.{Flags, SpanId, TraceId, TraceId128}
import natchez.Kernel
import org.typelevel.ci._

// TODO could this use OpenTelemetry's TextMapPropagator instead of doing it ourselves?
object ZipkinKernel {

  // TODO this propagates the headers in the B3 multi-header format, but maybe it should convert to OpenTelemetry / W3C's tracecontext header?
  def asKernel(t: TraceId): Kernel = Kernel {
    B3MultiHeaders.fromTraceId(t).map { case (k, v) => CIString(k) -> v }
  }

  def asTraceId(kernel: Kernel): Option[TraceId] = {
    val headers = kernel.toHeaders

    headers.get(ci"X-B3-SpanId")
      .flatMap(SpanId.fromString)
      .map {
        val traceId = headers.get(ci"X-B3-TraceId").map(TraceId128(_))
        val parentId = headers.get(ci"X-B3-ParentSpanId").flatMap(SpanId.fromString)
        val sampled = headers.get(ci"X-B3-Sampled").collect {
          case "1" => true
          case "0" => false
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
