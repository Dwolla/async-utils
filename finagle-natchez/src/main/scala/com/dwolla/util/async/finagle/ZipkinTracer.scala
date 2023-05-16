package com.dwolla.util.async.finagle

import cats.Functor
import cats.effect.Sync
import cats.effect.std.Env
import cats.syntax.all._
import com.comcast.ip4s._
import com.dwolla.util.async.finagle.ZipkinTracer.alwaysSample
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.Tracer
import zipkin2.finagle.http.HttpZipkinTracer


object ZipkinTracer {
  val alwaysSample: Float = 1.0f

  def apply[F[_] : Env : Sync](localServiceName: String): F[Tracer] =
    ZipkinTracerConfig(localServiceName) >>= (ZipkinTracer(_))

  def apply[F[_] : Sync](zipkinTracer: ZipkinTracerConfig): F[Tracer] =
    Sync[F].delay {
      HttpZipkinTracer.create(zipkinTracer.build(), new NullStatsReceiver())
    }

}

object ZipkinTracerConfig {
  def apply[F[_] : Env : Functor](localServiceName: String): F[ZipkinTracerConfig] =
    Env[F]
      .get("OTEL_EXPORTER_ZIPKIN_ENDPOINT")
      .map {
        _
          .flatMap(SocketAddress.fromString)
          .foldl(new ZipkinTracerConfig(localServiceName))(_ withHost _)
      }
}

class ZipkinTracerConfig(val host: SocketAddress[_],
                         val initialSampleRate: Float,
                         val tlsEnabled: Boolean,
                         val localServiceName: String,
                         val compressionEnabled: Boolean,
                         val hostHeader: String,
                         val path: String) {

  def this(localServiceName: String) =
    this(
      SocketAddress(host"127.0.0.1", port"9411"),
      alwaysSample,
      false,
      localServiceName,
      true,
      "zipkin",
      "/api/v2/spans"
    )

  def withHost(update: SocketAddress[_]): ZipkinTracerConfig =
    new ZipkinTracerConfig(update, initialSampleRate, tlsEnabled, localServiceName, compressionEnabled, hostHeader, path)

  private[finagle] def build(): HttpZipkinTracer.Config =
    HttpZipkinTracer.Config
      .builder()
      .hostHeader(hostHeader)
      .host(host.toString())
      .initialSampleRate(initialSampleRate)
      .tlsEnabled(tlsEnabled)
      .localServiceName(localServiceName)
      .compressionEnabled(compressionEnabled)
      .path(path)
      .build()
}
