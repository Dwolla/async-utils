package com.dwolla.util.async

import cats._
import cats.data._
import cats.effect._
import cats.tagless._
import cats.tagless.syntax.all._

import scala.concurrent.Future

object stdlib extends ToAsyncFunctorKOps {
  implicit def scalaFutureAsyncFunctorK[F[_]]: Future ~~> F = new (Future ~~> F) {
    override def asyncMapK[Alg[_[_]] : FunctorK](alg: Alg[Future])
                                                (implicit AlgR: Alg[ReaderT[Future, Alg[Future], *]],
                                                 F: Async[F]): Alg[F] =
      AlgR.mapK(provide[F](alg))
  }

  def provide[F[_]] = new PartiallyAppliedProvide[F]
}

class PartiallyAppliedProvide[F[_]](private val dummy: Boolean = true) extends AnyVal {
  def apply[R](service: R)
              (implicit F: Async[F]): Kleisli[Future, R, *] ~> F =
    λ[Kleisli[Future, R, *] ~> F] { r =>
      Async[F].fromFuture(Sync[F].delay(r(service)))
    }
}
