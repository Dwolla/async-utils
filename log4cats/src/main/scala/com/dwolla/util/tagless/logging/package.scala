package com.dwolla.util.tagless

package object logging {
  implicit def toLoggingWeaveMaterializerOps[Alg[_[_]], F[_]](alg: Alg[F]): LoggingWeaveMaterializerOps[Alg, F] =
    new LoggingWeaveMaterializerOps(alg)
}
