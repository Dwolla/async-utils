package com.dwolla.util.async.finagle

import com.twitter.finagle.tracing.{Flags, SpanId, TraceId, TraceId128}
import natchez.Kernel
import org.typelevel.ci.CIString

// TODO could this use OpenTelemetry's TextMapPropagator instead of doing it ourselves?
object ZipkinKernel {

  // TODO this propagates the headers in the B3 multi-header format, but maybe it should convert to OpenTelemetry / W3C's tracecontext header?
  def asKernel(t: TraceId): Kernel = Kernel {
    B3MultiHeaders.fromTraceId(t).map { case (k, v) => CIString(k) -> v }
  }

  def asTraceId(kernel: Kernel): Option[TraceId] = {
    val headers = kernel.toHeaders
    def header(name: String): Option[String] = headers.get(CIString(name))

    header(B3MultiHeaders.SpanIdHeader)
      .flatMap(SpanId.fromString)
      .map {
        val traceId = header(B3MultiHeaders.TraceIdHeader).map(TraceId128(_))
        val parentId = header(B3MultiHeaders.ParentSpanIdHeader).flatMap(SpanId.fromString)
        // B3: "Debug is encoded as X-B3-Flags: 1. Absent or any other value can be ignored." Debug implies accept.
        val debug = header(B3MultiHeaders.FlagsHeader).contains("1")
        // B3: "some tracers propagated X-B3-Sampled as true or false ... a lenient implementation may accept them."
        val sampled = header(B3MultiHeaders.SampledHeader).collect {
          case "1" | "true" => true
          case "0" | "false" => false
        }

        TraceId(
          _traceId = traceId.flatMap(_.low),
          _parentId = parentId,
          _,
          _sampled = if (debug) Some(true) else sampled,
          flags = if (debug) Flags().setDebug else Flags(),
          traceIdHigh = traceId.flatMap(_.high),
        )
      }
  }
}
