package com.dwolla.util.async

import cats._
import cats.data._
import cats.effect._
import com.twitter.util

object TwitterFutureService {
  class PartiallyAppliedProvide[F[_]](private val dummy: Boolean = true) extends AnyVal {
    def apply[R](service: R)
                (implicit F: Async[F]): Kleisli[util.Future, R, *] ~> F =
      λ[Kleisli[util.Future, R, *] ~> F] { r =>
        Async[F].asyncF { cb =>
          Sync[F].delay {
            r(service).respond {
              case util.Return(x) => cb(Right(x))
              case util.Throw(x) => cb(Left(x))
            }

            ()
          }
        }
      }
  }

  def provide[F[_]] = new PartiallyAppliedProvide[F]
}
