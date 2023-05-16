package example.dwolla

import cats.tagless.FunctorK
import example.foo.FooService

class UsesFooService[F[_]](val fooService: example.foo.FooService.FooService[F]) {
  implicitly[FunctorK[FooService.FooService]]
}
