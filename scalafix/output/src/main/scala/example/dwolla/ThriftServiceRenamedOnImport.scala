package example.dwolla

import cats.tagless.FunctorK
import example.foo.{FooService => TFooService}

class ThriftServiceRenamedOnImport[F[_]](val fooService: TFooService.FooService[F]) {
  implicitly[FunctorK[TFooService.FooService]]
}
