package com.dwolla.util.async.finagle

import cats.*
import cats.data.*
import cats.effect.std.Env
import cats.effect.syntax.all.*
import cats.effect.{Trace as _, tracing as _, *}
import cats.syntax.all.*
import cats.tagless.*
import cats.tagless.aop.*
import cats.tagless.syntax.all.*
import com.dwolla.util.async.finagle.ThriftClient.initialAcquire
import com.dwolla.util.async.twitter.*
import com.twitter.finagle
import com.twitter.util.{Closable, Future}
import natchez.{Span, Trace}

/**
 * Build a Finagle Thrift client using the given Thrift
 * method-per-endpoint algebra and the given destination. Optionally,
 * configure the client by setting options on a
 * `ThriftClientConfiguration` object passed to `apply`. The client
 * wraps each call in a natchez `Span.SpanKind.Client` span, a child of
 * the ambient `Trace[G]`'s span, and sends that span's IDs to the
 * server as the Finagle `TraceId` of the request.
 */
object TracedThriftClient {
  // The OpenTelemetry RPC semantic conventions are still in development; these
  // are the keys finagle-otel4s's TracedThriftServer puts on its server spans.
  private val RpcSystemName: String = "rpc.system.name"
  private val RpcMethod: String = "rpc.method"

  /**
   * Builds a Finagle client safely translated from Twitter Future to
   * the given `G[_] : Async` effect type, with each call traced as a
   * natchez client span that's propagated to the server. The lifecycle
   * of the client is managed in a Cats Effect `Resource`.
   *
   * This method is partially applied; the instance returned accepts
   * several more parameters. It's implemented this way because the
   * caller must specify the `Alg` parameter, but the other type
   * parameters are normally able to be inferred and therefore not
   * explicitly set. See the `PartiallyAppliedThriftClient.apply`
   * Scaladoc for more details on the additional parameters.
   *
   * @tparam Alg the method-per-endpoint trait on which to base the Finagle client
   * @return a Finagle Thrift client that will operate in
   */
  def apply[Alg[_[_]] <: AnyRef {def asClosable: Closable}] = new PartiallyAppliedThriftClient[Alg]()

  class PartiallyAppliedThriftClient[Alg[_[_]] <: AnyRef {def asClosable: Closable}] private[TracedThriftClient](val dummy: Unit = ()) extends AnyVal {
    /*
     * The overloads without an `Instrument[Alg]` remain for binary compatibility with code
     * compiled against earlier versions. They can't name a client span after the Thrift
     * method, so they keep their original behavior: the ambient span's kernel is converted
     * to a Finagle `TraceId`, and Finagle sends a child of it that it alone records. They're
     * hidden from source code, where the overloads that take an `Instrument[Alg]` would be
     * ambiguous with them. They're declared first because Scala 2.12 numbers the overloads'
     * extension methods (`apply$extension0`, `apply$extension1`, ...) in declaration order.
     */
    private[TracedThriftClient] def apply[F[_] : Async : Env, G[_] : Async : Trace](name: String,
                                                                                    dest: String)
                                                                                   (implicit
                                                                                    AlgR: Alg[Kleisli[Future, Alg[Future], *]],
                                                                                    FK: FunctorK[Alg],
                                                                                    MPE: HigherKindedToMethodPerEndpoint[Alg],
                                                                                   ): Resource[F, Alg[G]] =
      zipkinConfiguration[F](name).flatMap(withAmbientTraceId[Alg, F, G](dest, _))

    private[TracedThriftClient] def apply[F[_] : Async, G[_] : Async : Trace](dest: String,
                                                                              config: ThriftClientConfiguration)
                                                                             (implicit
                                                                              AlgR: Alg[Kleisli[Future, Alg[Future], *]],
                                                                              FK: FunctorK[Alg],
                                                                              HK2MPE: HigherKindedToMethodPerEndpoint[Alg],
                                                                             ): Resource[F, Alg[G]] =
      withAmbientTraceId[Alg, F, G](dest, config)

    /**
     * Builds a client that reports to Zipkin through Finagle's own
     * tracing, in addition to the natchez client span it opens for each
     * call. See the other overload for details about the client span.
     *
     * @param name the local service name to be set on the Zipkin tracer
     * @param dest the destination whence the Thrift interface is being served.
     *             supports Finagle's `Resolver` interface (because the destination
     *             is handed directly to the Finagle layer)
     * @tparam F the effect type in which to construct the Resource that will manage the client. Requires an `Async` instance, but not a `Trace`
     * @tparam G the effect type in which the client should operate. Requires both an `Async` and `Trace` constraint.
     * @return a Finagle Thrift client that will operate in
     */
    def apply[F[_] : Async : Env, G[_] : Async : Trace](name: String,
                                                        dest: String)
                                                       (implicit
                                                        AlgR: Alg[Kleisli[Future, Alg[Future], *]],
                                                        I: Instrument[Alg],
                                                        MPE: HigherKindedToMethodPerEndpoint[Alg],
                                                       ): Resource[F, Alg[G]] =
      zipkinConfiguration[F](name).flatMap(withClientSpans[Alg, F, G](dest, _))

    /**
     * Each call is wrapped in a natchez span of kind `Span.SpanKind.Client`, a child
     * of the ambient `Trace[G]`'s span, named `{service}/{method}`, where `{service}`
     * is the Thrift algebra's `Instrumentation#algebraName` and `{method}` is the
     * `Instrumentation#methodName`. The span has the attributes `rpc.system.name`
     * (`thrift`) and `rpc.method` (the same `{service}/{method}` as the span name).
     * A failed call attaches its error to the span, as natchez's `Trace#span` does.
     *
     * If the client span's kernel carries B3 headers, its trace ID, span ID, and
     * sampling decision are sent to the server as the Finagle `TraceId` of the
     * request, exactly as they are: Finagle doesn't derive a child span from it,
     * so the server's span is a child of the client span. Otherwise, the call is
     * made without a Finagle `TraceId`, and Finagle starts a new trace for it.
     *
     * The `Tracer` set in `config` still records Finagle's own annotations for the
     * call, under the client span's IDs.
     *
     * @param dest   the destination whence the Thrift interface is being served.
     *               supports Finagle's `Resolver` interface (because the destination
     *               is handed directly to the Finagle layer)
     * @param config an instance of `ThriftClientConfiguration` containing configuration settings
     * @tparam F the effect type in which to construct the Resource that will manage the client. Requires an `Async` instance, but not a `Trace`
     * @tparam G the effect type in which the client should operate. Requires both an `Async` and `Trace` constraint.
     * @return a Finagle Thrift client that will operate in
     */
    def apply[F[_] : Async, G[_] : Async : Trace](dest: String,
                                                  config: ThriftClientConfiguration)
                                                 (implicit
                                                  AlgR: Alg[Kleisli[Future, Alg[Future], *]],
                                                  I: Instrument[Alg],
                                                  HK2MPE: HigherKindedToMethodPerEndpoint[Alg],
                                                 ): Resource[F, Alg[G]] =
      withClientSpans[Alg, F, G](dest, config)
  }

  private def zipkinConfiguration[F[_] : Sync : Env](name: String): Resource[F, ThriftClientConfiguration] =
    ZipkinTracer[F](name)
      .map(ThriftClientConfiguration().withTracer(_))
      .toResource

  private def withClientSpans[Alg[_[_]] <: AnyRef {def asClosable: Closable}, F[_] : Async, G[_] : Async : Trace](dest: String,
                                                                                                                   config: ThriftClientConfiguration)
                                                                                                                  (implicit
                                                                                                                   AlgR: Alg[Kleisli[Future, Alg[Future], *]],
                                                                                                                   I: Instrument[Alg],
                                                                                                                   HK2MPE: HigherKindedToMethodPerEndpoint[Alg],
                                                                                                                  ): Resource[F, Alg[G]] =
    managed(initialAcquire[Alg, F](dest, config))(alg => AlgR.instrument.mapK(new ProvideFutureAlgInClientSpan[G, Alg](alg)))

  private def withAmbientTraceId[Alg[_[_]] <: AnyRef {def asClosable: Closable}, F[_] : Async, G[_] : Async : Trace](dest: String,
                                                                                                                      config: ThriftClientConfiguration)
                                                                                                                     (implicit
                                                                                                                      AlgR: Alg[Kleisli[Future, Alg[Future], *]],
                                                                                                                      FK: FunctorK[Alg],
                                                                                                                      HK2MPE: HigherKindedToMethodPerEndpoint[Alg],
                                                                                                                     ): Resource[F, Alg[G]] =
    managed(initialAcquire[Alg, F](dest, config))(TracedTwitterFutureAsyncFunctorK[G].asyncMapK[Alg])

  // release the client Finagle built: the Alg[G] mapped from it doesn't delegate asClosable to it
  private def managed[Client <: AnyRef {def asClosable: Closable}, F[_] : Async, A](acquire: F[Client])
                                                                                   (toTraced: Client => A): Resource[F, A] =
    Resource.make(acquire)(client => liftFuture(Sync[F].delay(client.asClosable.close())))
      .map(toTraced)

  /**
   * Runs each Thrift call in a client span, with the client span's IDs as the call's Finagle `TraceId`.
   */
  private class ProvideFutureAlgInClientSpan[G[_] : Async : Trace, Alg[_[_]]](alg: Alg[Future])
    extends (Instrumentation[Kleisli[Future, Alg[Future], *], *] ~> G) {
    override def apply[A](instrumentation: Instrumentation[Kleisli[Future, Alg[Future], *], A]): G[A] = {
      val rpcMethod = s"${instrumentation.algebraName}/${instrumentation.methodName}"

      Trace[G].span(rpcMethod, Span.Options.Defaults.withSpanKind(Span.SpanKind.Client)) {
        Trace[G].put(RpcSystemName -> "thrift", RpcMethod -> rpcMethod) >>
          Trace[G].kernel.map(ZipkinKernel.asTraceId).flatMap { maybeTraceId =>
            liftFuture[G] {
              Sync[G].delay {
                maybeTraceId.fold(instrumentation.value(alg)) { traceId =>
                  // terminal, so Finagle's client stack sends exactly this TraceId (on every attempt,
                  // including retries) instead of deriving a child span from it that nothing else records
                  finagle.tracing.Trace.letId(traceId, terminal = true) {
                    // finally provide the algebra instance to the Kleisli implementation
                    // this is safe because it's captured by a Sync[G].delay
                    instrumentation.value(alg)
                  }
                }
              }
            }
          }
      }
    }
  }
}
