package com.dwolla.util.tagless.logging

import cats.*
import cats.effect.syntax.all.*
import cats.effect.{Trace as _, *}
import cats.syntax.all.*
import cats.tagless.aop.*
import cats.tagless.syntax.all.*
import org.typelevel.log4cats.Logger

final class LoggingWeaveMaterializerOps[Alg[_[_]], F[_]](val alg: Alg[F]) extends AnyVal {
  def withMethodLogging(implicit
                        aspect: Aspect[Alg, Show, Show],
                        logger: Logger[F],
                        F: MonadCancelThrow[F],
                       ): Alg[F] =
    alg.weave.mapK(new LoggingWeaveMaterializer[F])
}

final class LoggingWeaveMaterializer[F[_] : MonadCancelThrow : Logger] extends (Aspect.Weave[F, Show, Show, *] ~> F) {
  override def apply[A](fa: Aspect.Weave[F, Show, Show, A]): F[A] = {
    val methodArguments = fa.domain.map { l =>
      l.map { a =>
        val value = a.target match {
          case Now(v) => a.instance.show(v)
          case _ => "unevaluated lazy value"
        }

        s"${a.name}=$value"
      }.mkString(", ")
    }.mkString_("(", ", ", ")")

    fa.codomain.target.guaranteeCase {
      case Outcome.Succeeded(out) =>
        out.flatMap { a =>
          Logger[F].info(s"${fa.algebraName}.${fa.codomain.name}$methodArguments returning ${fa.codomain.instance.show(a)}")
        }
      case Outcome.Errored(ex) =>
        Logger[F].warn(ex)(s"${fa.algebraName}.${fa.codomain.name}$methodArguments raised an exception")
      case Outcome.Canceled() =>
        Logger[F].warn(s"${fa.algebraName}.${fa.codomain.name}$methodArguments was canceled")
    }
  }
}
