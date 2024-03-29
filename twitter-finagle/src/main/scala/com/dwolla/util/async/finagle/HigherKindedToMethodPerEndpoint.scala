package com.dwolla.util.async.finagle

import com.twitter.util.Future

import scala.reflect.ClassTag

/**
 * Since Scrooge removed the higher-kinded service traits implemented in {{{com.twitter.util.Future}}},
 * and inlined those method definitions into `MethodPerEndpoint` traits, we had to reintroduce the
 * higher-kinded traits with our `AddCatsTaglessInstances` scalafix rule.
 *
 * The problem is that Finagle uses reflection to figure out what class to instantiate, and the
 * reflection logic specifically looks for `ServiceName$$MethodPerEndpoint`, where `ServiceName`
 * is the {{{com.twitter.finagle.thrift.GeneratedThriftService}}} object. After running the Scalafix
 * rule, the `ServiceName$$MethodPerEndpoint` definition will extend `ServiceName[Future]`, but
 * passing an instance of `ServiceName[Future]` isn't good enough: it seems like it must be
 * the `MethodPerEndpoint`.
 *
 * This typeclass encodes the relationship between the higher-kinded service trait and its
 * `MethodPerEndpoint` counterpart, so that if we have an instance of the service trait, we can
 * convert to and from the related `MethodPerEndpoint`.

 * @tparam Alg the higher-kinded service trait whose relationship to a `MethodPerEndpoint` trait is being encoded by the instance
 */
trait HigherKindedToMethodPerEndpoint[Alg[_[_]]] {
  type MPE <: Alg[Future] with AnyRef
  val mpeClassTag: ClassTag[MPE]

  def toMethodPerEndpoint(hk: Alg[Future]): MPE
}

object HigherKindedToMethodPerEndpoint {
  def apply[Alg[_[_]]](implicit hktmpe: HigherKindedToMethodPerEndpoint[Alg]): hktmpe.type = hktmpe

  implicit def mpeClassTag[Alg[_[_]]](implicit MPE: HigherKindedToMethodPerEndpoint[Alg]): scala.reflect.ClassTag[MPE.MPE] =
    MPE.mpeClassTag

  implicit def toToMethodPerEndpointOps[Alg[_[_]]](alg: Alg[Future]): ToMethodPerEndpointOps[Alg] =
    new ToMethodPerEndpointOps(alg)
}

class ToMethodPerEndpointOps[Alg[_[_]]](val alg: Alg[Future]) extends AnyVal {
  def toMethodPerEndpoint(implicit HK2MPE: HigherKindedToMethodPerEndpoint[Alg]): HK2MPE.MPE =
    HK2MPE.toMethodPerEndpoint(alg)
}
