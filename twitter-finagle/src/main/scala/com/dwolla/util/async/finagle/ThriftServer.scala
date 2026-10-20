package com.dwolla.util.async.finagle

import cats._
import cats.effect._
import cats.effect.std.Dispatcher
import cats.tagless._
import cats.tagless.implicits._
import com.dwolla.util.async.twitter._
import com.twitter.finagle.{ListeningServer, Thrift}
import com.twitter.util._

import scala.concurrent.ExecutionContext

object ThriftServer {
  def apply[F[_] : Async, Thrift[_[_]] : FunctorK : HigherKindedToMethodPerEndpoint](addr: String, iface: Thrift[F])
                                                                                    (implicit ec: ExecutionContext): Resource[F, ListeningServer] =
    Dispatcher.parallel[F]
      .map(unsafeMapKToFuture(_, iface))
      .flatMap(t => Resource.make(acquire[F, Thrift](addr, t))(release[F]))

  private def unsafeMapKToFuture[F[_] : Sync, Thrift[_[_]] : FunctorK](dispatcher: Dispatcher[F], iface: Thrift[F])
                                                                      (implicit ec: ExecutionContext): Thrift[Future] =
    iface.mapK(new (F ~> Future) {
      override def apply[A](fa: F[A]): Future[A] =
        dispatcher.unsafeToTwitterFuture(fa)
    })

  private def acquire[F[_] : Sync, Thrift[_[_]] : HigherKindedToMethodPerEndpoint](addr: String, iface: Thrift[Future]): F[ListeningServer] =
    Sync[F].delay {
      Thrift.server.serveIface(addr, HigherKindedToMethodPerEndpoint[Thrift].toMethodPerEndpoint(iface))
    }

  private def release[F[_] : Async](s: ListeningServer): F[Unit] =
    liftFuture(Sync[F].delay(s.close()))

}
