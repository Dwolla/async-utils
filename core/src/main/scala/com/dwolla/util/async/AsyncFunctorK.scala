package com.dwolla.util.async

import cats.data.ReaderT
import cats.effect.Async
import cats.tagless.FunctorK

trait AsyncFunctorK[F[_], G[_]] {
  def asyncMapK[Alg[_[_]] : FunctorK](alg: Alg[F])
                                     (implicit AlgR: Alg[ReaderT[F, Alg[F], *]], G: Async[G]): Alg[G]
}

object AsyncFunctorK {
  def apply[F[_], G[_]](implicit AFK: F ~~> G): F ~~> G = AFK
}

class AsyncFunctorKOps[Alg[_[_]], F[_]](val alg: Alg[F]) extends AnyVal {
  def asyncMapK[G[_] : Async](implicit
                              F: FunctorK[Alg],
                              AlgR: Alg[ReaderT[F, Alg[F], *]],
                              AFK: F ~~> G): Alg[G] = AsyncFunctorK[F, G].asyncMapK(alg)
}

trait ToAsyncFunctorKOps {
  implicit def toAsyncFunctorKOps[Alg[_[_]], F[_]](alg: Alg[F]) =
    new AsyncFunctorKOps[Alg, F](alg)
}
