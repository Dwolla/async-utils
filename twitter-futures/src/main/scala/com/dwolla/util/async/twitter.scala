package com.dwolla.util.async

import cats.*
import cats.data.*
import cats.effect.*
import cats.effect.std.Dispatcher
import cats.effect.syntax.all.*
import cats.syntax.all.*
import cats.tagless.*
import cats.tagless.syntax.all.*
import com.dwolla.util.async.twitter.CancelledViaCatsEffect
import com.twitter.util

import java.util.concurrent.CancellationException
import scala.concurrent.ExecutionContext
import scala.util.control.{NoStackTrace, NonFatal}

object twitter extends ToAsyncFunctorKOps {
  implicit def twitterFutureAsyncFunctorK[F[_]]: util.Future ~~> F = new (util.Future ~~> F) {
    override def asyncMapK[Alg[_[_]] : FunctorK](alg: Alg[util.Future])
                                                (implicit AlgR: Alg[ReaderT[util.Future, Alg[util.Future], *]],
                                                 F: Async[F]): Alg[F] =
      AlgR.mapK(provide[F](alg))
  }

  def provide[F[_]] = new PartiallyAppliedProvide[F]

  def liftFuture[F[_]] = new PartiallyAppliedLiftFuture[F]

  implicit class DispatcherToTwitterFutureOps[F[_]](private val dispatcher: Dispatcher[F]) extends AnyVal {
    /**
     * Submits `fa` to the `Dispatcher` and returns a Twitter `Future` that completes with its
     * value or error. This is the reverse of `liftFuture`.
     *
     * Interrupting the returned `Future` (e.g. when Finagle gives up on a request because the
     * client timed out or disconnected) fails it with the interrupt's `Throwable` and cancels
     * the fiber running `fa`. When the interrupt is `CancelledViaCatsEffect`, raised
     * because a fiber awaiting this `Future` through `liftFuture` was canceled, that fiber
     * sees itself canceled.
     *
     * If `fa` is canceled some other way, such as by canceling itself or by the `Dispatcher`
     * closing while `fa` runs, the returned `Future` fails with a `java.util.concurrent.CancellationException`,
     * where the Scala `Future` returned by `Dispatcher#unsafeToFuture` would never complete.
     * Observing that cancellation is why `Sync[F]` is needed: the `Future` is completed by a
     * finalizer added to `fa`.
     *
     * Another implementation option might be `com.twitter.util.FuturePool.unbounded(dispatcher.unsafeRunSync(fa))`,
     * but the implementation of `unsafeRunSync` involves a lock, so this async style is expected to
     * work better by folks in the cats-effect Discord.
     *
     * @param fa the effect to run
     * @param F  used to complete the Twitter `Future` when `fa` is canceled
     * @param ec `ExecutionContext` where the Twitter `Future` will be completed when the Scala `Future` output by `Dispatcher.unsafeToFutureCancelable` completes
     */
    def unsafeToTwitterFuture[A](fa: F[A])
                                (implicit F: Sync[F], ec: ExecutionContext): util.Future[A] = {
      val promise = util.Promise[A]()
      val (result, cancel) = dispatcher.unsafeToFutureCancelable {
        fa.onCancel(F.delay(promise.updateIfEmpty(util.Throw(EffectCancelledInCatsEffect))).void)
      }

      promise.setInterruptHandler { case interrupt =>
        // completing the Promise before canceling means the caller sees the interrupt, not the
        // onCancel finalizer's exception; it also covers an effect canceled before it started,
        // whose finalizer never runs. If the effect's outcome got there first, there's nothing to cancel
        if (promise.updateIfEmpty(util.Throw(interrupt))) {
          // the Dispatcher throws if it's closed; it cancels its fibers itself when that happens
          // anyway, and an interrupt handler must not throw
          try {
            val _ = cancel()
          } catch {
            case NonFatal(_) => ()
          }
        }
      }

      result.onComplete { outcome =>
        val _ = promise.updateIfEmpty(util.Try.fromScala(outcome))
      }

      promise
    }
  }

  private[async] case object CancelledViaCatsEffect
    extends CancellationException("Cancelled via cats-effect")
      with NoStackTrace

  /**
   * Fails a Twitter `Future` returned by `unsafeToTwitterFuture` when the effect behind it was
   * canceled in cats-effect (e.g. it canceled itself, or its `Dispatcher` closed), rather than
   * because the `Future` was interrupted.
   */
  private[async] case object EffectCancelledInCatsEffect
    extends CancellationException("The effect was cancelled in cats-effect")
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
