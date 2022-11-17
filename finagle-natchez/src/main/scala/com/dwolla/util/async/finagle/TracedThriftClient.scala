package com.dwolla.util.async.finagle

import cats.data._
import cats.effect.{Trace => _, tracing => _, _}
import cats.syntax.all._
import cats.tagless._
import com.dwolla.util.async.finagle.ThriftClient.initialAcquire
import com.dwolla.util.async.twitter._
import com.twitter.util.{Closable, Future}
import natchez.Trace

import scala.language.reflectiveCalls

/**
 * Build a Finagle Thrift client using the given Thrift
 * method-per-endpoint algebra and the given destination. Optionally,
 * configure the client by setting options on a
 * `ThriftClientConfiguration` object passed to `apply`. The client
 * will propagate spans from the ambient `Trace[G]` using Finagle's
 * built-in Zipkin support.
 */
object TracedThriftClient {
  /**
   * Builds a Finagle client safely translated from Twitter Future to
   * the given `G[_] : Async` effect type, with trace propagation from
   * the ambient `Trace[G]` to Finagle's Zipkin support. The lifecycle
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
    /**
     * @param dest the destination whence the Thrift interface is being served.
     *             supports Finagle's `Resolver` interface (because the destination
     *             is handed directly to the Finagle layer)
     * @tparam F the effect type in which to construct the Resource that will manage the client. Requires an `Async` instance, but not a `Trace`
     * @tparam G the effect type in which the client should operate. Requires both an `Async` and `Trace` constraint.
     * @return a Finagle Thrift client that will operate in
     */
    def apply[F[_] : Async, G[_] : Async : Trace](dest: String)
                                                 (implicit
                                                  AlgR: Alg[Kleisli[Future, Alg[Future], *]],
                                                  FK: FunctorK[Alg],
                                                  MPE: HigherKindedToMethodPerEndpoint[Alg],
                                                 ): Resource[F, Alg[G]] =
      apply[F, G](dest, ThriftClientConfiguration())

    /**
     * @param dest the destination whence the Thrift interface is being served.
     *             supports Finagle's `Resolver` interface (because the destination
     *             is handed directly to the Finagle layer)
     * @param config an instance of `ThriftClientConfiguration` containing configuration settings
     * @tparam F the effect type in which to construct the Resource that will manage the client. Requires an `Async` instance, but not a `Trace`
     * @tparam G the effect type in which the client should operate. Requires both an `Async` and `Trace` constraint.
     * @return a Finagle Thrift client that will operate in
     */
    def apply[F[_] : Async, G[_] : Async : Trace](dest: String,
                                                  config: ThriftClientConfiguration)
                                                 (implicit
                                                  AlgR: Alg[Kleisli[Future, Alg[Future], *]],
                                                  FK: FunctorK[Alg],
                                                  HK2MPE: HigherKindedToMethodPerEndpoint[Alg],
                                                 ): Resource[F, Alg[G]] = {
      val acquire = initialAcquire[Alg, F](dest, config).map(TracedTwitterFutureAsyncFunctorK[G].asyncMapK[Alg])
      val release: Alg[G] => F[Unit] = alg => liftFuture(Sync[F].delay(alg.asClosable.close()))

      Resource.make(acquire)(release)
    }
  }
}
