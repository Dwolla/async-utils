package com.dwolla.util.async.finagle.otel4s

import com.twitter.finagle.tracing.{Record, TraceId, Tracer}

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

/**
 * A Finagle `Tracer` registered through `META-INF/services` (like zipkin-finagle's is), so Finagle's
 * `LoadService` installs it on every client and server that doesn't set a tracer explicitly.
 * Finagle instantiates it reflectively, so what it records lives in the companion.
 * It leaves sampling undecided (`sampleTrace` returns `None`), so it never turns an undecided
 * `TraceId` into a sampled one on the clients it's installed on.
 */
class RecordingFinagleTracer extends Tracer {
  RecordingFinagleTracer.instantiated.set(true)

  override def record(record: Record): Unit = {
    RecordingFinagleTracer.recorded.add(record)
    ()
  }

  override def sampleTrace(traceId: TraceId): Option[Boolean] = None
}

object RecordingFinagleTracer {
  private val recorded = new ConcurrentLinkedQueue[Record]()
  private val instantiated = new AtomicBoolean(false)

  def reset(): Unit = recorded.clear()
  def records(): List[Record] = recorded.asScala.toList
  def loadedByFinagle: Boolean = instantiated.get()
}
