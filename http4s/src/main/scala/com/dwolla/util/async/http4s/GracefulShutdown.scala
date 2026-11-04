package com.dwolla.util.async.http4s

import cats._
import cats.effect._
import cats.syntax.all._
import org.http4s._
import org.http4s.headers.Connection
import org.http4s.syntax.all._
import org.typelevel.ci._

import scala.concurrent.duration._

/**
 * Pieces for shutting an http4s server down gracefully behind a health-checked
 * registry or load balancer. They share a [[DrainSignal]]: once it is draining,
 * the health route fails, responses ask clients to close their connections, and
 * the server is released only after the registry has had time to notice.
 */
object GracefulShutdown {
  val DefaultDrainPeriod: FiniteDuration = 10.seconds

  /**
   * `GET /health` answers 204 while serving and 503 once the signal is draining.
   * No other path is matched, so these routes can be combined with a service's own.
   */
  def healthRoutes[F[_]: Monad](signal: DrainSignal[F]): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case req if req.method == Method.GET && req.pathInfo == path"/health" =>
        signal.isDraining.map { draining =>
          Response[F](if (draining) Status.ServiceUnavailable else Status.NoContent)
        }
    }

  /**
   * While the signal is draining, every response from `app` carries exactly one
   * `Connection: close` header, replacing any `Connection` header `app` set, so
   * clients stop reusing connections to a server that is going away. The header only
   * signals HTTP/1.x clients: Ember strips connection-specific headers on HTTP/2. Errors
   * `app` raises, rather than returns as a response, are answered by the server's error
   * handler outside this middleware, so wrap `app` in `ErrorHandling` (or equivalent) to
   * cover them.
   */
  def closeConnectionsWhileDraining[F[_]: Monad](signal: DrainSignal[F])(app: HttpApp[F]): HttpApp[F] =
    HttpApp[F] { req =>
      (app.run(req), signal.isDraining).mapN { (response, draining) =>
        if (draining) response.putHeaders(Connection(ci"close")) else response
      }
    }

  /**
   * Releases `server` only after the signal has been draining for `drainPeriod`,
   * which gives a registry or load balancer time to see the failing health check
   * and stop sending new requests.
   *
   * If `server` fails to start, nothing is waited for and the signal is left alone.
   *
   * Services must also set their server's shutdown timeout short (for example,
   * Ember's `withShutdownTimeout`), because that is how long requests already in
   * flight get to finish after the drain period. The drain period plus that
   * timeout must fit within the orchestrator's stop timeout, or the process is
   * killed mid-shutdown.
   */
  def drainBeforeRelease[F[_]: Temporal, A](signal: DrainSignal[F], drainPeriod: FiniteDuration)
                                           (server: Resource[F, A]): Resource[F, A] =
    server <* Resource.onFinalize(signal.startDraining >> Temporal[F].sleep(drainPeriod))
}
