package com.dwolla.util.async

import cats._
import cats.data._
import cats.effect._

import scala.concurrent.Future

object ScalaFutureService {
  class PartiallyAppliedProvide[F[_]](private val dummy: Boolean = true) extends AnyVal {
    def apply[R](service: R)
                (implicit F: Async[F]): Kleisli[Future, R, *] ~> F =
      λ[Kleisli[Future, R, *] ~> F] { r =>
        Async[F].fromFuture(Sync[F].delay(r(service)))
      }
  }

  def provide[F[_]] = new PartiallyAppliedProvide[F]
}
