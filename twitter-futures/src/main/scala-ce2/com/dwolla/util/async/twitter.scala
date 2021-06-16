package com.dwolla.util.async

import cats._
import cats.data._
import cats.effect._
import cats.effect.syntax.all._
import cats.syntax.all._
import cats.tagless._
import cats.tagless.syntax.all._
import com.twitter.util

object twitter extends ToAsyncFunctorKOps {
  implicit def twitterFutureAsyncFunctorK[F[_] : ContextShift]: util.Future ~~> F = new (util.Future ~~> F) {
    override def asyncMapK[Alg[_[_]] : FunctorK](alg: Alg[util.Future])
                                                (implicit AlgR: Alg[ReaderT[util.Future, Alg[util.Future], *]],
                                                 F: Async[F]): Alg[F] =
      AlgR.mapK(provide[F](alg))
  }

  def provide[F[_]] = new PartiallyAppliedProvide[F]

  def liftFuture[F[_]] = new PartiallyAppliedLiftFuture[F]
}

class PartiallyAppliedProvide[F[_]](private val dummy: Boolean = true) extends AnyVal {
  def apply[R](service: R)
              (implicit F: Async[F],
               CS: ContextShift[F]): Kleisli[util.Future, R, *] ~> F =
    λ[Kleisli[util.Future, R, *] ~> F] { r =>
      twitter.liftFuture[F] {
        Sync[F].delay {
          r(service)
        }
      }
    }
}

class PartiallyAppliedLiftFuture[F[_]] {
  def apply[A](fa: F[util.Future[A]])
              (implicit
               F: Async[F],
               CS: ContextShift[F]): F[A] =
    Async[F].asyncF[A] { cb =>
      fa.map {
        _.respond {
          case util.Return(a) => cb(Right(a))
          case util.Throw(ex) => cb(Left(ex))
        }
      }.void
    }.guarantee(ContextShift[F].shift)
}

