package com.dwolla.util.async

import cats.effect.*
import cats.effect.std.*
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import com.dwolla.util.async.twitter.{CancelledViaCatsEffect, liftFuture}
import com.twitter.util.{Duration as _, *}
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.scalacheck.effect.PropF
import org.scalacheck.{Prop, Test}

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

  test("cancelling one fiber awaiting a shared Twitter Future fails, rather than hangs or cancels, the other fibers awaiting it") {
    TestControl.executeEmbed {
      for {
        promise <- IO(Promise[Int]()).flatTap(failWhenInterrupted[IO, Int])
        cancelledFiberStarted <- CountDownLatch[IO](1)
        bystanderFiberStarted <- CountDownLatch[IO](1)
        cancelledFiber <- liftFuture[IO](cancelledFiberStarted.release.as(promise)).start
        bystanderFiber <- liftFuture[IO](bystanderFiberStarted.release.as(promise)).start
        _ <- cancelledFiberStarted.await >> bystanderFiberStarted.await
        _ <- cancelledFiber.cancel
        bystanderOutcome <- bystanderFiber.join
      } yield {
        bystanderOutcome match {
          case Outcome.Errored(ex) => assertEquals(ex, CancelledViaCatsEffect)
          case other => fail(s"expected the bystander fiber to fail, but its outcome was $other")
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

  private def failWhenInterrupted[F[_] : Sync, A](p: Promise[A]): F[Unit] =
    Sync[F].delay {
      p.setInterruptHandler { case ex =>
        val _ = p.updateIfEmpty(Throw(ex))
        ()
      }
    }

}
