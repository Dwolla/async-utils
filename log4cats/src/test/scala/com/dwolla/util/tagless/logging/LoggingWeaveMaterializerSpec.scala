package com.dwolla.util.tagless.logging

import cats.Show
import cats.effect.IO
import cats.tagless.Derive
import cats.tagless.aop.Aspect
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.scalacheck.effect.PropF
import org.typelevel.log4cats.testing.TestingLogger

class LoggingWeaveMaterializerSpec
  extends CatsEffectSuite
    with ScalaCheckEffectSuite {

  test("instrumented logging") {
    PropF.forAllF { (foo: Int, bar: String, returnValue: Either[Throwable, Boolean]) =>
      implicit val testLogger: TestingLogger[IO] = TestingLogger.impl[IO]()

      val target = new TestWeaveTarget[IO] {
        override def foo(foo: Int, bar: String): IO[Boolean] =
          IO.fromEither(returnValue)
      }

      target
        .withMethodLogging
        .foo(foo, bar)
        .attempt
        .product(testLogger.logged)
        .map {
          case (Right(out), logged) =>
            assertEquals(Right(out), returnValue)
            assertEquals(logged, Vector(TestingLogger.INFO(
              message = s"TestWeaveTarget.foo(foo=$foo, bar=$bar) returning $out",
              throwOpt = None
            )))
          case (Left(out), logged) =>
            assertEquals(Left(out), returnValue)
            assertEquals(logged, Vector(TestingLogger.WARN(
              message = s"TestWeaveTarget.foo(foo=$foo, bar=$bar) raised an exception",
              throwOpt = Option(out)
            )))
        }
    }
  }
}

trait TestWeaveTarget[F[_]] {
  def foo(foo: Int, bar: String): F[Boolean]
}

object TestWeaveTarget {
  implicit def show[A]: Show[A] = Show.fromToString

  implicit val loggingTestWeaveTargetAspect: Aspect[TestWeaveTarget, Show, Show] = Derive.aspect
}
