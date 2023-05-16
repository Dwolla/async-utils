/*rule = AdaptHigherKindedThriftCode*/
package example.dwolla

import cats.tagless.FunctorK
import example.foo.{FooService => TFooService}

class ThriftServiceRenamedOnImport[F[_]](val fooService: TFooService[F]) {
  implicitly[FunctorK[TFooService]]
}
