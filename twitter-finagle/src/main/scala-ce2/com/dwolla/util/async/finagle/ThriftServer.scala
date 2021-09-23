package com.dwolla.util.async.finagle

import cats._
import cats.effect._
import cats.tagless._
import cats.tagless.implicits._
import com.dwolla.util.async.twitter.liftFuture
import com.twitter.finagle.{ListeningServer, Thrift}
import com.twitter.util.{Future, Promise}

object ThriftServer {
  def apply[F[_] : Effect : ContextShift, Thrift[_[_]] <: AnyRef : FunctorK](addr: String, iface: Thrift[F]): F[Nothing] =
    Resource.make(acquire[F, Thrift](addr, unsafeMapKToFuture(iface)))(release[F]).use[F, Nothing](_ => Async[F].never[Nothing])

  private def unsafeMapKToFuture[F[_] : Effect, Thrift[_[_]] <: AnyRef : FunctorK](iface: Thrift[F]): Thrift[Future] =
    iface.mapK(new (F ~> Future) {
      override def apply[A](fa: F[A]): Future[A] = {
        val p = Promise[A]()

        Effect[F].runAsync(fa) {
          case Left(ex) => IO(p.setException(ex))
          case Right(x) => IO(p.setValue(x))
        }
          .unsafeRunSync()

        p
      }
    })

  private def acquire[F[_] : Sync, Thrift[_[_]] <: AnyRef](addr: String, iface: Thrift[Future]): F[ListeningServer] =
    Sync[F].delay(Thrift.server.serveIface(addr, iface))

  private def release[F[_] : Async : ContextShift](s: ListeningServer): F[Unit] =
    liftFuture(Sync[F].delay(s.close()))

}
