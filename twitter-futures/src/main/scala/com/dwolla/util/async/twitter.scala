package com.dwolla.util.async

import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*
import cats.tagless.*
import cats.tagless.syntax.all.*
import com.dwolla.util.async.twitter.CancelledViaCatsEffect
import com.twitter.util

import java.util.concurrent.CancellationException
import scala.util.control.NoStackTrace

object twitter extends ToAsyncFunctorKOps {
  implicit def twitterFutureAsyncFunctorK[F[_]]: util.Future ~~> F = new (util.Future ~~> F) {
    override def asyncMapK[Alg[_[_]] : FunctorK](alg: Alg[util.Future])
                                                (implicit AlgR: Alg[ReaderT[util.Future, Alg[util.Future], *]],
                                                 F: Async[F]): Alg[F] =
      AlgR.mapK(provide[F](alg))
  }

  def provide[F[_]] = new PartiallyAppliedProvide[F]

  def liftFuture[F[_]] = new PartiallyAppliedLiftFuture[F]

  private[async] case object CancelledViaCatsEffect
    extends CancellationException("Cancelled via cats-effect")
      with NoStackTrace
}

class PartiallyAppliedProvide[F[_]](private val dummy: Boolean = true) extends AnyVal {
  def apply[R](service: R)
              (implicit F: Async[F]): Kleisli[util.Future, R, *] ~> F =
    λ[Kleisli[util.Future, R, *] ~> F] { r =>
      twitter.liftFuture[F] {
        Sync[F].delay {
          r(service)
        }
      }
    }
}

class PartiallyAppliedLiftFuture[F[_]] {
  def apply[A](ffa: F[util.Future[A]])
              (implicit F: Async[F]): F[A] =
    Ref.of[F, Boolean](false).flatMap { cancellationRequested =>
      Async[F].async[A] { cb: (Either[Throwable, A] => Unit) =>
        ffa
          .flatMap { fa =>
            Sync[F].delay {
              fa.respond {
                case util.Return(a) => cb(Right(a))
                case util.Throw(ex) => cb(Left(ex))
              }
            }
          }
          .map { fa =>
            (cancellationRequested.set(true) >> Sync[F].delay {
              fa.raise(CancelledViaCatsEffect)
            }).some
          }
      }
        .recoverWith(recoverFromCancelledViaCatsEffect(cancellationRequested))
    }

  /**
   * According to CE maintainer Daniel Spiewak in Discord, there's
   * a race condition in the CE runtime that means sometimes it will
   * see the future as completed (with the `CancelledViaCatsEffect`
   * exception) before it transitions into the canceled state. This
   * `recoverWith` should prevent that from happening.
   *
   * The future may also have been interrupted by a different fiber
   * awaiting the same future, in which case this fiber was not
   * cancelled and should see the failure instead of cancelling itself.
   */
  private final def recoverFromCancelledViaCatsEffect[A](cancellationRequested: Ref[F, Boolean])
                                                        (implicit F: Async[F]): PartialFunction[Throwable, F[A]] = {
    case CancelledViaCatsEffect =>
      cancellationRequested.get.ifM(
        Async[F].canceled >> Async[F].never,
        CancelledViaCatsEffect.raiseError[F, A],
      )
  }
}
