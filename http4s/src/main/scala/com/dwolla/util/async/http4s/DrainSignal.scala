package com.dwolla.util.async.http4s

import cats.effect._
import cats.syntax.all._

/**
 * A one-way switch that records that a server has begun draining, so
 * health checks and responses can start telling clients to go elsewhere.
 * Once draining has started, it never stops.
 */
sealed abstract class DrainSignal[F[_]] {
  def isDraining: F[Boolean]

  /** Idempotent: starting to drain a signal that is already draining does nothing. */
  def startDraining: F[Unit]
}

object DrainSignal {
  def apply[F[_]: Concurrent]: F[DrainSignal[F]] =
    Deferred[F, Unit].map { draining =>
      new DrainSignal[F] {
        override val isDraining: F[Boolean] = draining.tryGet.map(_.isDefined)
        override val startDraining: F[Unit] = draining.complete(()).void
      }
    }
}
