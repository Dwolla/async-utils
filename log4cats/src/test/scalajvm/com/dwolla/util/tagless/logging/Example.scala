package test

import cats.*
import cats.effect.*
import cats.tagless.*
import cats.tagless.aop.*
import com.dwolla.util.tagless.logging.*
import org.typelevel.log4cats.slf4j.Slf4jFactory

trait MyAlgebra[F[_]] {
  def foo(foo: Int, bar: String): F[Boolean]
}

object MyAlgebra {
  implicit val showInt: Show[Int] = Show.fromToString
  implicit val showString: Show[String] = Show.show[String](identity)
  implicit val showBoolean: Show[Boolean] = Show.fromToString

  implicit val loggingMyAlgebraAspect: Aspect[MyAlgebra, Show, Show] = Derive.aspect
}

object MyApp extends IOApp.Simple {
  private val fakeMyAlgebra: MyAlgebra[IO] = new MyAlgebra[IO] {
    override def foo(foo: Int, bar: String): IO[Boolean] =
      IO.pure(true)
  }

  override def run: IO[Unit] =
    Slf4jFactory.create[IO].create.flatMap { implicit logger =>
      fakeMyAlgebra
        .withMethodLogging
        .foo(42, "The Answer to the Ultimate Question of Life, the Universe, and Everything")
        .flatMap(IO.println)
    }
}
