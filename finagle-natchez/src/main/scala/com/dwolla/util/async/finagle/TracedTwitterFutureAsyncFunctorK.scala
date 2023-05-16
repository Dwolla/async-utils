package com.dwolla.util.async.finagle

import cats._
import cats.data._
import cats.effect.{Trace => _, _}
import cats.syntax.all._
import cats.tagless._
import cats.tagless.syntax.all._
import com.dwolla.util.async.finagle.TracedTwitterFutureAsyncFunctorK.ProvideFutureAlgWithTracing
import com.dwolla.util.async.twitter._
import com.dwolla.util.async.~~>
import com.twitter.finagle
import com.twitter.util.Future

object TracedTwitterFutureAsyncFunctorK {
  def apply[F[_] : natchez.Trace]: TracedTwitterFutureAsyncFunctorK[F] =
    new TracedTwitterFutureAsyncFunctorK[F]

  class ProvideFutureAlgWithTracing[F[_] : Async : natchez.Trace, Alg[_[_]]](alg: Alg[Future]) extends (Kleisli[Future, Alg[Future], *] ~> F) {
    override def apply[A](fa: Kleisli[Future, Alg[Future], A]): F[A] =
      OptionT(natchez.Trace[F].kernel.map(ZipkinKernel.asTraceId))
        .semiflatMap { traceId =>
          liftFuture[F] {
            Sync[F].delay {
              // TODO check Trace.isActivelyTracing?
              finagle.tracing.Trace.letId(traceId) {
                // finally provide the algebra instance to the Kleisli implementation
                // this is safe because it's captured by a Sync[F].delay
                fa(alg)
              }
            }
          }
        }
        .getOrElseF {
          liftFuture[F] {
            Sync[F].delay {
              fa(alg)
            }
          }
        }
  }
}

class TracedTwitterFutureAsyncFunctorK[F[_] : natchez.Trace] extends (Future ~~> F) {
  override def asyncMapK[Alg[_[_]] : FunctorK](alg: Alg[Future])
                                              (implicit AlgR: Alg[ReaderT[Future, Alg[Future], *]], F: Async[F]): Alg[F] =
    AlgR.mapK(new ProvideFutureAlgWithTracing(alg))
}
