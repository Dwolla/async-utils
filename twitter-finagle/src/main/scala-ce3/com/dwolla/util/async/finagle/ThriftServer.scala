package com.dwolla.util.async.finagle

import cats._
import cats.effect._
import cats.effect.std.Dispatcher
import cats.syntax.all._
import cats.tagless._
import cats.tagless.implicits._
import com.dwolla.util.async.twitter._
import com.twitter.finagle.{ListeningServer, Thrift}
import com.twitter.util._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object ThriftServer {
  def apply[F[_] : Async, Thrift[_[_]] : FunctorK : HigherKindedToMethodPerEndpoint](addr: String, iface: Thrift[F])
                                                                                    (implicit ec: ExecutionContext): Resource[F, ListeningServer] =
    Dispatcher.parallel[F]
      .evalMap(unsafeMapKToFuture(_, iface).pure[F])
      .flatMap(t => Resource.make(acquire[F, Thrift](addr, t))(release[F]))

  private def unsafeMapKToFuture[F[_], Thrift[_[_]] : FunctorK](dispatcher: Dispatcher[F], iface: Thrift[F])
                                                               (implicit ec: ExecutionContext): Thrift[Future] =
    iface.mapK(new (F ~> Future) {
      override def apply[A](fa: F[A]): Future[A] = {
        // another implementation option might be `com.twitter.util.FuturePool.unbounded(dispatcher.unsafeRunSync(fa))`,
        // but the implementation of `unsafeRunSync` involves a lock, so this async style is expected to
        // work better by folks in the cats-effect Discord.
        val p = Promise[A]()
        dispatcher.unsafeToFuture(fa)
          .onComplete {
            case Success(a) => p.setValue(a)
            case Failure(ex) => p.setException(ex)
          }
        p
      }
    })

  private def acquire[F[_] : Sync, Thrift[_[_]] : HigherKindedToMethodPerEndpoint](addr: String, iface: Thrift[Future]): F[ListeningServer] =
    Sync[F].delay {
      Thrift.server.serveIface(addr, HigherKindedToMethodPerEndpoint[Thrift].toMethodPerEndpoint(iface))
    }

  private def release[F[_] : Async](s: ListeningServer): F[Unit] =
    liftFuture(Sync[F].delay(s.close()))

}
