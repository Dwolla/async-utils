import cats.data._
import cats.effect._
import cats.tagless._
import com.dwolla.util.async.stdlib._

import scala.concurrent.{ExecutionContext, Future}

trait FooService[F[_]] {
  def foo(i: Int): F[Unit]
}

object FooService {
  implicit def FooServiceReaderT[F[_]]: FooService[ReaderT[F, FooService[F], *]] =
    Derive.readerT[FooService, F]
  implicit val FooServiceFunctorK: FunctorK[FooService] = Derive.functorK
}

class FutureFoo(implicit ec: ExecutionContext) extends FooService[Future] {
  def foo(i: Int): Future[Unit] = Future(println(i))
}

object Demo extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val fooService: FooService[IO] =
      new FutureFoo().asyncMapK[IO]

    fooService.foo(-1) // doesn't do anything because the `IO[Unit]` is discarded

    fooService.foo(42).as(ExitCode.Success) // prints 42
  }
}
