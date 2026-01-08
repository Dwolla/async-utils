package com.dwolla.util.async

import cats.effect.*
import cats.effect.std.*
import cats.syntax.all.*
import com.dwolla.util.async.twitter.liftFuture
import com.twitter.util.{Duration as _, *}
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.scalacheck.{Prop, Test}
import org.scalacheck.effect.PropF

import java.util.concurrent.CancellationException
import scala.concurrent.duration.*

class TwitterFutureAsyncMapKTests extends CatsEffectSuite with ScalaCheckEffectSuite {
  override def munitIOTimeout: Duration = 1.minute

  override protected def scalaCheckTestParameters: Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(100000)

  test("lift a Twitter Future into IO") {
    PropF.forAllF { (i: Int) =>
      for {
        promise <- IO(Promise[Int]())
        (x, _) <- liftFuture[IO](IO(promise)).both(IO(promise.setValue(i)))
      } yield {
        assertEquals(x, i)
      }
    }
  }

  test("cancelling a running Twitter Future lifted into IO should interrupt the underlying Twitter Future") {
    for {
      promise <- IO(Promise[Int]())
      startedLatch <- CountDownLatch[IO](1)
      fiber <- IO.uncancelable { poll => // we want only the Future to be cancellable
        poll(liftFuture[IO](startedLatch.release.as(promise))).start
      }
      _ <- startedLatch.await
      _ <- fiber.cancel
    } yield {
      assert(promise.isInterrupted.isDefined)
    }
  }

  test("a running Twitter Future lifted into IO can be completed or cancelled") {
    PropF.forAllF { (i: Option[Int]) =>
      (Supervisor[IO](await = true), Dispatcher.parallel[IO](await = true))
        .tupled
        .use { case (supervisor, dispatcher) =>
          for {
            capturedInterruptionThrowable <- Deferred[IO, Throwable]
            twitterPromise <- IO(new Promise[Option[Int]]()).flatTap(captureThrowableOnInterruption(dispatcher, capturedInterruptionThrowable))
            startedLatch <- CountDownLatch[IO](1)
            promiseFiber <- IO.uncancelable { poll => // we want only the Future to be cancellable
              supervisor.supervise(poll(liftFuture[IO](startedLatch.release.as(twitterPromise))))
            }
            _ <- startedLatch.await
            _ <- completeOrCancel(i, twitterPromise, promiseFiber)
            cancelledRef <- Ref[IO].of(false)
            outcome <- promiseFiber.joinWith(cancelledRef.set(true).as(None))
            wasCancelled <- cancelledRef.get

            expectCancellation = i.isEmpty
            _ <- interceptMessageIO[CancellationException]("Cancelled via cats-effect") {
              capturedInterruptionThrowable
                .get
                .timeout(10.millis)
                .map(_.asLeft)
                .rethrow // interceptMessageIO works by throwing an exception, so we need to rethrow it to get the message
            }
              .whenA(expectCancellation)
          } yield {
            assertEquals(outcome, i)
            assertEquals(wasCancelled, i.as(false).getOrElse(true))
            assertEquals(Option(CancelledViaCatsEffect).filter(_ => expectCancellation), twitterPromise.isInterrupted)
          }
        }
    }
  }

  // just here to make sure we understand how Twitter Future / Promise handles interruption
  test("the Twitter Future cancellation protocol") {
    Prop.forAll { (throwable: Throwable) =>
      val promise = Promise[Int]()

      promise.raise(throwable)

      assertEquals(promise.isInterrupted, throwable.some)
    }
  }

  private def captureThrowableOnInterruption[F[_] : Sync, A](dispatcher: Dispatcher[F],
                                                             capture: Deferred[F, Throwable])
                                                            (p: Promise[A]): F[Unit] =
    Sync[F].delay {
      p.setInterruptHandler { case ex =>
        dispatcher.unsafeRunSync(capture.complete(ex).void)
      }
    }

  private def completeOrCancel[F[_] : Sync, A](maybeA: Option[A],
                                               promise: Promise[Option[A]],
                                               fiber: Fiber[F, Throwable, Option[A]]): F[Unit] =
    maybeA match {
      case Some(a) => Sync[F].delay {
        promise.setValue(a.some)
      }
      case None =>
        fiber.cancel
    }

}
