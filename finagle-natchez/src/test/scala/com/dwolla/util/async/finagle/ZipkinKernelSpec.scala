package com.dwolla.util.async.finagle

import cats.syntax.all.*
import com.twitter.finagle.tracing.{Flags, SpanId, TraceId}
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen}
import org.typelevel.ci.*

class ZipkinKernelSpec extends ScalaCheckSuite {
  private val genNonZeroLong: Gen[Long] = Gen.long.suchThat(_ != 0L)

  private implicit val arbTraceId: Arbitrary[TraceId] = Arbitrary {
    for {
      traceIdLow <- genNonZeroLong
      traceIdHigh <- Gen.option(genNonZeroLong)
      parentId <- Gen.option(genNonZeroLong)
      spanId <- genNonZeroLong
      sampled <- Gen.option(Gen.oneOf(true, false))
    } yield TraceId(
      _traceId = SpanId(traceIdLow).some,
      _parentId = parentId.map(SpanId(_)),
      spanId = SpanId(spanId),
      _sampled = sampled,
      flags = Flags(),
      traceIdHigh = traceIdHigh.map(SpanId(_)),
    )
  }

  private def hex(l: Long): String = f"$l%016x"

  property("asKernel renders a Finagle TraceId as B3 multi-headers") {
    forAll { (t: TraceId) =>
      val expected: Map[CIString, String] =
        Map(
          ci"X-B3-TraceId" -> (t.traceIdHigh.map(id => hex(id.toLong)).orEmpty + hex(t.traceId.toLong)),
          ci"X-B3-SpanId" -> hex(t.spanId.toLong),
        ) ++
          t._parentId.map(id => ci"X-B3-ParentSpanId" -> hex(id.toLong)) ++
          Map(ci"X-B3-Sampled" -> (if (t.sampled.contains(false)) "0" else "1"))

      assertEquals(ZipkinKernel.asKernel(t).toHeaders, expected)
    }
  }

  property("asTraceId reverses asKernel for trace IDs with a sampling decision") {
    forAll { (t: TraceId, sampled: Boolean) =>
      val decidedTraceId = t.copy(_sampled = sampled.some)
      val roundTripped = ZipkinKernel.asKernel(decidedTraceId).some.flatMap(ZipkinKernel.asTraceId)

      assertEquals(roundTripped, decidedTraceId.some)
      // TraceId's equality ignores the sampling decision, so compare it separately
      assertEquals(roundTripped.flatMap(_.sampled), sampled.some)
    }
  }
}
