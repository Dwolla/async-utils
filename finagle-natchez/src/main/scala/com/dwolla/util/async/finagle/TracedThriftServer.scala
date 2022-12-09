package com.dwolla.util.async.finagle

import cats._
import cats.data.Kleisli
import cats.effect._
import cats.effect.std.Dispatcher
import cats.tagless._
import cats.tagless.aop._
import cats.tagless.implicits._
import com.comcast.ip4s.{IpAddress, SocketAddress}
import com.dwolla.util.async.finagle.HigherKindedToMethodPerEndpoint._
import com.dwolla.util.async.twitter._
import com.twitter.finagle._
import com.twitter.finagle.tracing.TraceId
import com.twitter.util._
import natchez._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/**
 * Starts a Finagle server that will host the given Thrift
 * method-per-endpoint implementation.
 */
object TracedThriftServer {
  /**
   *
   * @param addr the socket address at which to listen for connections. (Typically `0.0.0.0:port`)
   * @param label the name to assign the service in the Zipkin traces
   * @param iface the Thrift method-per-endpoint implementation. Must be implemented in `Kleisli[F, Span[F], *]` so the span continued from Zipkin can be injected into the program.
   * @param entryPoint the Natchez `EntryPoint` responsible for creating `Span` instances based on the Trace IDs coming from Finagle/Zipkin
   * @tparam Thrift
   * @return a `Resource[F, ListeningServer]` managing the lifecycle of the underlying Finagle server
   */
  def apply[F[_] : Async, Thrift[_[_]] : HigherKindedToMethodPerEndpoint : Instrument](addr: SocketAddress[IpAddress],
                                                                                       label: String,
                                                                                       iface: Thrift[Kleisli[F, Span[F], *]],
                                                                                       entryPoint: EntryPoint[F]
                                                                                      )
                                                                                      (implicit ec: ExecutionContext): Resource[F, ListeningServer] =
    Dispatcher.parallel[F]
      .map(unsafeMapKToFuture(_, iface.instrument, entryPoint))
      .flatMap(t => Resource.make(acquire(addr, label, t))(release[F]))

  private def unsafeMapKToFuture[F[_] : Async, Thrift[_[_]] : FunctorK](dispatcher: Dispatcher[F],
                                                                        iface: Thrift[Instrumentation[Kleisli[F, Span[F], *], *]],
                                                                        entryPoint: EntryPoint[F],
                                                                       )
                                                                       (implicit ec: ExecutionContext): Thrift[Future] =
    iface.mapK(new (Instrumentation[Kleisli[F, Span[F], *], *] ~> Future) {
      override def apply[A](fa: Instrumentation[Kleisli[F, Span[F], *], A]): Future[A] =
        currentTraceId().flatMap { maybeTraceId =>
          val p = Promise[A]()

          dispatcher.unsafeToFuture {
            entryPoint.continueOrElseRoot(
              s"${fa.algebraName}.${fa.methodName}",
              maybeTraceId
                .map(ZipkinKernel.asKernel)
                .getOrElse(Kernel(Map.empty))
            )
              .use(fa.value.run)
          }
            .onComplete {
              case Success(a) => p.setValue(a)
              case Failure(ex) => p.setException(ex)
            }

          p
        }
    })

  private def currentTraceId(): Future[Option[TraceId]] =
    Future(com.twitter.finagle.tracing.Trace.idOption)

  private def acquire[F[_] : Sync, Thrift[_[_]] : HigherKindedToMethodPerEndpoint](addr: SocketAddress[IpAddress],
                                                                                   label: String,
                                                                                   iface: Thrift[Future]): F[ListeningServer] =
    Sync[F].delay {
      Thrift
        .server
        .withLabel(label)
        .serveIface(addr.toInetSocketAddress, iface.toMethodPerEndpoint)
    }

  private def release[F[_] : Async](s: ListeningServer): F[Unit] =
    liftFuture(Sync[F].delay(s.close()))
}
