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
    def apply[G[_] : Async](dest: String, config: ThriftClientConfiguration = ThriftClientConfiguration())
                           (implicit
                            AlgR: Alg[ReaderT[Future, Alg[Future], *]],
                            FK: FunctorK[Alg],
                            MPE: HigherKindedToMethodPerEndpoint[Alg],
                           ): Resource[G, Alg[G]] = {
      val acquire =
        Sync[G].delay {
          Thrift
            .client
            .withSessionPool.maxSize(config.sessionPoolMax)
            .withRequestTimeout(config.requestTimeout)
            .withSession.acquisitionTimeout(config.sessionAcquisitionTimeout)
            .withTransport.connectTimeout(config.transportConnectTimeout)
            .withRetryBudget(config.retryBudget)
            .build(dest)(HigherKindedToMethodPerEndpoint[Alg].mpeClassTag)
        }
          .map(_.asyncMapK[G])
      val release: Alg[G] => G[Unit] = alg => liftFuture(Sync[G].delay(alg.asClosable.close()))

      Resource.make(acquire)(release)
    }
  }
}
