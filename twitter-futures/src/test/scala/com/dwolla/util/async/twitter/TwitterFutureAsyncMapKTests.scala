package com.dwolla.util.async

import cats.*
import cats.effect.*
import cats.effect.std.*
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import cats.effect.syntax.all.*
import com.dwolla.util.async.twitter.{CancelledViaCatsEffect, liftFuture}
import com.twitter.util.{Duration as _, *}
import munit.{AnyFixture, CatsEffectSuite, ScalaCheckEffectSuite}
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.effect.PropF
import org.scalacheck.{Arbitrary, Gen, Prop, Test}

import java.util.concurrent.CancellationException
import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

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

  private val supervisorAndDispatcher = ResourceTestLocalFixture("supervisorAndDispatcher",
    Supervisor[IO](await = true).product(Dispatcher.sequential[IO](await = true))
  )

  override def munitFixtures: Seq[AnyFixture[?]] = super.munitFixtures ++ Seq(supervisorAndDispatcher)

  test("a running Twitter Future lifted into IO can be completed (as success or failure) or cancelled") {
    PropF.forAllF { (i: Outcome[IO, Throwable, Int]) =>
      val (supervisor, dispatcher) = supervisorAndDispatcher()

      TestControl.executeEmbed {
        for {
          expectedResult <- i.embed(CancelledViaCatsEffect.raiseError[IO, Int]).attempt
          capturedInterruptionThrowable <- Deferred[IO, Throwable]
          twitterPromise <- IO(new Promise[Int]()).flatTap(captureThrowableOnInterruption(dispatcher, capturedInterruptionThrowable))
          startedLatch <- CountDownLatch[IO](1)
          promiseFiber <- IO.uncancelable { poll => // we want only the Future to be cancellable
            supervisor.supervise(poll(liftFuture[IO](startedLatch.release.as(twitterPromise))))
          }
          _ <- startedLatch.await

          (outcome, _) <- promiseFiber.join.both(completeOrCancel(i, twitterPromise, promiseFiber))

          outcomeEmittedValue <- outcome.embed(CancelledViaCatsEffect.raiseError[IO, Int]).attempt

          expectCancellation = i.isCanceled
          _ <- interceptMessageIO[CancellationException]("Cancelled via cats-effect") {
            capturedInterruptionThrowable
              .get
              .timeout(10.millis)
              .map(_.asLeft)
              .rethrow // interceptMessageIO works by throwing an exception, so we need to rethrow it to get the message
          }
            .whenA(expectCancellation)
        } yield {
          assertEquals(outcomeEmittedValue, expectedResult)
          assertEquals(outcome.isCanceled, i.isCanceled)
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

  private def genOutcome[F[_] : Applicative, A: Arbitrary]: Gen[Outcome[F, Throwable, A]] =
    Gen.oneOf(
      arbitrary[A].map(_.pure[F]).map(Outcome.succeeded[F, Throwable, A]),
      Gen.const(new RuntimeException("arbitrary exception") with NoStackTrace).map(Outcome.errored[F, Throwable, A]),
      Gen.const(Outcome.canceled[F, Throwable, A]),
    )
  private implicit def arbOutcome[F[_] : Applicative, A: Arbitrary]: Arbitrary[Outcome[F, Throwable, A]] = Arbitrary(genOutcome)

  private def captureThrowableOnInterruption[F[_] : Sync, A](dispatcher: Dispatcher[F],
                                                             capture: Deferred[F, Throwable])
                                                            (p: Promise[A]): F[Unit] =
    Sync[F].delay {
      p.setInterruptHandler { case ex =>
        dispatcher.unsafeRunSync(capture.complete(ex).void)
      }
    }

  private def completeOrCancel[F[_] : Async, A](maybeA: Outcome[F, Throwable, A],
                                                promise: Promise[A],
                                                fiber: Fiber[F, Throwable, A]): F[Unit] =
    maybeA match {
      case Outcome.Succeeded(fa) =>
        fa.flatMap(a => Sync[F].delay(promise.setValue(a))).void

      case Outcome.Errored(ex) =>
        // If the fiber is in the background (i.e. not joined) when it completes with an exception,
        // the IO runtime will print its stacktrace to stderr. We always plan to `join` the fiber
        // our tests are complete, so this feels like a false error report.

        // To work around the issue, we delay the completion of the promise to make sure the fiber
        // is joined before the promise is completed with the exception.
        Sync[F].delay(promise.setException(ex)).delayBy(10.millis)

      case Outcome.Canceled() =>
        fiber.cancel
    }

}
