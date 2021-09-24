package com.dwolla.util

import cats.data.ReaderT

package object async {
  type ~~>[F[_], G[_]] = AsyncFunctorK[F, G]
  type AlgReaderT[F[_], Alg[_[_]]] = Alg[ReaderT[F, Alg[F], *]]
}
