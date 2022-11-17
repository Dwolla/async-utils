package com.dwolla.util.async.finagle

import cats.data._
import cats.effect._
import cats.syntax.all._
import cats.tagless._
import com.dwolla.util.async.twitter._
import com.twitter.finagle.Thrift
import com.twitter.util.{Closable, Future}

import scala.language.reflectiveCalls

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
                           ): Resource[G, Alg[G]] = {
      val acquire = initialAcquire(dest, config).map(_.asyncMapK[G])
      val release: Alg[G] => G[Unit] = alg => liftFuture(Sync[G].delay(alg.asClosable.close()))

      Resource.make(acquire)(release)
    }
  }

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
        .build[MPE.MPE](dest)(MPE.mpeClassTag)
    }
}
