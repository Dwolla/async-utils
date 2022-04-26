/*rule = AdaptHigherKindedThriftCode*/
package example.dwolla

import cats.tagless.FunctorK
import example.dwolla.ThriftServiceTypeAlias.TFooService
import example.foo.FooService

object ThriftServiceTypeAlias {
  type TFooService[F[_]] = FooService[F]
}

class ThriftServiceTypeAlias[F[_]](val fooService: TFooService[F]) {
  implicitly[FunctorK[TFooService]]
}
