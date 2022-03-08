package com.dwolla.util.async.finagle

import cats.data._
import cats.effect._
import cats.syntax.all._
import cats.tagless._
import com.dwolla.util.async._
import com.dwolla.util.async.twitter._
import com.twitter.finagle.Thrift
import com.twitter.util.Closable

import scala.language.reflectiveCalls
import scala.reflect.ClassTag

object ThriftClient {
  def apply[Alg[_[_]] <: AnyRef {def asClosable: Closable}] = new PartiallyAppliedThriftClient[Alg]()

  class PartiallyAppliedThriftClient[Alg[_[_]] <: AnyRef {def asClosable: Closable} ] private[ThriftClient] (val dummy: Unit = ()) extends AnyVal {
    def apply[G[_] : Async : ContextShift, F[_]](dest: String, config: ThriftClientConfiguration = ThriftClientConfiguration())
                                                (implicit
                                                 AFK: F ~~> G,
                                                 AlgR: Alg[ReaderT[F, Alg[F], *]],
                                                 CT: ClassTag[Alg[F]],
                                                 FK: FunctorK[Alg]): Resource[G, Alg[G]] = {
      val acquire =
        Sync[G].delay(
          Thrift
            .client
            .withSessionPool.maxSize(config.sessionPoolMax)
            .withRequestTimeout(config.requestTimeout)
            .withTransport.connectTimeout(config.transportConnectTimeout)
            .withSession.acquisitionTimeout(config.sessionAcquisitionTimeout)
            .withRetryBudget(config.retryBudget)
            .build[Alg[F]](dest)
        ).map(_.asyncMapK[G])
      val release: Alg[G] => G[Unit] = alg => liftFuture(Sync[G].delay(alg.asClosable.close()))

      Resource.make(acquire)(release)
    }
  }
}
