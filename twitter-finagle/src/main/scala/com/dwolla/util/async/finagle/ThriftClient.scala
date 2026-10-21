package com.dwolla.util.async.finagle

import cats.data.*
import cats.effect.*
import cats.tagless.*
import com.dwolla.util.async.twitter.*
import com.twitter.finagle.Thrift
import com.twitter.util.{Closable, Future}

object ThriftClient {
  def apply[Alg[_[_]] <: AnyRef {def asClosable: Closable}] = new PartiallyAppliedThriftClient[Alg]()

  class PartiallyAppliedThriftClient[Alg[_[_]] <: AnyRef {def asClosable: Closable}] private[ThriftClient] (val dummy: Unit = ()) extends AnyVal {
    def apply[G[_] : Async](dest: String)
                           (implicit
                            AlgR: Alg[ReaderT[Future, Alg[Future], *]],
                            FK: FunctorK[Alg],
                            MPE: HigherKindedToMethodPerEndpoint[Alg],
                           ): Resource[G, Alg[G]] =
      apply(dest, ThriftClientConfiguration())

    def apply[G[_] : Async](dest: String, config: ThriftClientConfiguration)
                           (implicit
                            AlgR: Alg[ReaderT[Future, Alg[Future], *]],
                            FK: FunctorK[Alg],
                            MPE: HigherKindedToMethodPerEndpoint[Alg],
                           ): Resource[G, Alg[G]] =
      Resource.make(initialAcquire[Alg, G](dest, config))(closeFinagleClient[Alg, G](_))
        .map(_.asyncMapK[G])
  }

  /**
   * Closes the client built by `initialAcquire`. Only that client can close the
   * Finagle client: the `Alg[G]` instances mapped from it don't delegate
   * `asClosable` to it, so closing one of them does nothing.
   */
  private def closeFinagleClient[Alg[_[_]] <: AnyRef {def asClosable: Closable}, F[_] : Async](client: Alg[Future]): F[Unit] =
    liftFuture(Sync[F].delay(client.asClosable.close()))

  private[finagle] def initialAcquire[Alg[_[_]], F[_] : Sync](dest: String,
                                                              config: ThriftClientConfiguration)
                                                             (implicit MPE: HigherKindedToMethodPerEndpoint[Alg]): F[MPE.MPE] =
    Sync[F].delay {
      Thrift
        .client
        .withSessionPool.maxSize(config.sessionPoolMax)
        .withRequestTimeout(config.requestTimeout)
        .withSession.acquisitionTimeout(config.sessionAcquisitionTimeout)
        .withTransport.connectTimeout(config.transportConnectTimeout)
        .withRetryBudget(config.retryBudget)
        .withTracer(config.tracer)
        .withStatsReceiver(config.statsReceiver)
        .build[MPE.MPE](dest)(MPE.mpeClassTag)
    }
}
