/*
 * this is a pared-down version of the code that will be generated
 * by scrooge, and then modified by our AddCatsTaglessInstances rule,
 * for a hypothetical "Foo" Thrift interface, containing a single
 * service named "Foo", with a method named "bar".
 */

package example.foo

import com.twitter.util.Future

object FooService {
  trait FooService[F[_]] {
    def bar: F[Unit]
  }
  
  object FooService {
    implicit def FooServiceInReaderT[F[_]]: FooService[({type Λ[β0] = _root_.cats.data.ReaderT[F, FooService[F], β0]})#Λ] =
      _root_.cats.tagless.Derive.readerT[FooService, F]

    implicit val FooServiceFunctorK: _root_.cats.tagless.FunctorK[FooService] = _root_.cats.tagless.Derive.functorK[FooService]

  }

  trait MethodPerEndpoint extends FooService[Future]
}
