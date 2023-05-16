package com.dwolla.util

package object async {
  type ~~>[F[_], G[_]] = AsyncFunctorK[F, G]
}
