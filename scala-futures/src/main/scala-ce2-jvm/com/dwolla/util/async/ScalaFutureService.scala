package com.dwolla.util.async

import cats._
import cats.data._
import cats.effect._
import cats.effect.syntax.all._

import scala.concurrent.{ExecutionContext, Future}

object ScalaFutureService {
  class PartiallyAppliedProvide[F[_]](private val dummy: Boolean = true) extends AnyVal {
    def apply[R](service: R)
                (implicit F: Async[F],
                 CS: ContextShift[F],
                 ec: ExecutionContext): Kleisli[Future, R, *] ~> F =
      λ[Kleisli[Future, R, *] ~> F] { r =>
        Async[F].asyncF { cb =>
          Sync[F].delay {
            r(service).onComplete(t => cb(t.toEither))
          }.guarantee(ContextShift[F].shift)
        }
      }
  }

  def provide[F[_]] = new PartiallyAppliedProvide[F]
}
