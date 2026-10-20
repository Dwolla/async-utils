package com.dwolla.util.async

import cats.effect.*
import cats.effect.std.*
import cats.syntax.all.*
import com.dwolla.util.async.twitter.*
import com.twitter.util.{Duration as _, *}
import munit.{AnyFixture, CatsEffectSuite, ScalaCheckEffectSuite}
import org.scalacheck.effect.PropF

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

class UnsafeToTwitterFutureSpec extends CatsEffectSuite with ScalaCheckEffectSuite {
  private implicit val ec: ExecutionContext = munitExecutionContext

  private val dispatcherFixture = ResourceSuiteLocalFixture("dispatcher", Dispatcher.parallel[IO](await = false))

  override def munitFixtures: Seq[AnyFixture[?]] = super.munitFixtures ++ Seq(dispatcherFixture)

  private def await[A](future: Future[A]): IO[A] =
    liftFuture[IO](IO(future)).timeout(10.seconds)

  private case class ExpectedException(message: String) extends RuntimeException(message) with NoStackTrace

  /**
   * An effect that never completes on its own, with latches that report when it starts running
   * and when it is canceled.
   */
  private case class NeverCompletingEffect(started: Deferred[IO, Unit],
                                           canceled: Deferred[IO, Unit]) {
    val effect: IO[Int] = (started.complete(()) >> IO.never[Int]).onCancel(canceled.complete(()).void)
  }

  private val neverCompletingEffect: IO[NeverCompletingEffect] =
    (Deferred[IO, Unit], Deferred[IO, Unit]).mapN(NeverCompletingEffect(_, _))

  test("the Twitter Future completes with the effect's value") {
    PropF.forAllF { (i: Int) =>
      await(dispatcherFixture().unsafeToTwitterFuture(IO.pure(i)))
        .map(assertEquals(_, i))
    }
  }

  test("the Twitter Future fails with the effect's error") {
    PropF.forAllF { (message: String) =>
      val expected = ExpectedException(message)

      await(dispatcherFixture().unsafeToTwitterFuture(expected.raiseError[IO, Int])).attempt
        .map(assertEquals(_, expected.asLeft[Int]))
    }
  }

  test("interrupting the Twitter Future cancels the running effect and fails the Future with the interrupt") {
    val interrupt = ExpectedException("interrupted by the caller")

    for {
      never <- neverCompletingEffect
      future <- IO(dispatcherFixture().unsafeToTwitterFuture(never.effect))
      _ <- never.started.get.timeout(10.seconds)
      _ <- IO(future.raise(interrupt))
      _ <- never.canceled.get.timeout(10.seconds)
      result <- await(future).attempt
    } yield assertEquals(result, interrupt.asLeft[Int])
  }

  test("interrupting the Twitter Future after the effect completed leaves the Future's value alone") {
    for {
      future <- IO(dispatcherFixture().unsafeToTwitterFuture(IO.pure(42)))
      _ <- await(future)
      _ <- IO(future.raise(ExpectedException("too late")))
      result <- await(future)
    } yield assertEquals(result, 42)
  }

  test("canceling a fiber awaiting the Twitter Future with liftFuture cancels the effect, and the awaiting fiber is canceled") {
    for {
      never <- neverCompletingEffect
      awaitingFiber <- liftFuture[IO](IO(dispatcherFixture().unsafeToTwitterFuture(never.effect))).start
      _ <- never.started.get.timeout(10.seconds)
      _ <- awaitingFiber.cancel
      _ <- never.canceled.get.timeout(10.seconds)
      outcome <- awaitingFiber.join
    } yield assert(outcome.isCanceled, s"expected the awaiting fiber to be canceled, but its outcome was $outcome")
  }

  test("the Twitter Future fails when the effect cancels itself") {
    await(dispatcherFixture().unsafeToTwitterFuture(IO.canceled.as(42))).attempt
      .map(assertEquals(_, EffectCancelledInCatsEffect.asLeft[Int]))
  }

  test("the Twitter Future fails when the Dispatcher closes while the effect is running") {
    for {
      never <- neverCompletingEffect
      (dispatcher, closeDispatcher) <- Dispatcher.parallel[IO](await = false).allocated
      future <- IO(dispatcher.unsafeToTwitterFuture(never.effect))
        .flatTap(_ => never.started.get.timeout(10.seconds))
        .onError { case _ => closeDispatcher }
      _ <- closeDispatcher.timeout(10.seconds)
      result <- await(future).attempt
    } yield assertEquals(result, EffectCancelledInCatsEffect.asLeft[Int])
  }
}
