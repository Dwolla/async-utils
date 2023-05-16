/*
 * this is a pared-down version of the code that used to be generated
 * by scrooge for a hypothetical "Foo" Thrift interface, containing
 * a single service named "Foo", with a method named "bar".
 *
 * It exists because we need the input for the AdaptHigherKindedThriftCode
 * rule to compile against the old structure. It's in a separate submodule
 * because we don't want any of our Scalafix rules to modify it, and we
 * don't want it to be available in this form when compiling the Scalafix
 * output module.
 */

package example.foo

import com.twitter.util.Future

@javax.annotation.Generated(value = Array("com.twitter.scrooge.Compiler"))
trait FooService[+MM[_]] extends _root_.com.twitter.finagle.thrift.ThriftService {
  def bar: MM[Unit]
}

object FooService {
  trait MethodPerEndpoint extends FooService[Future]

  implicit def FooServiceInReaderT[F[_]]: FooService[({type Λ[β0] = _root_.cats.data.ReaderT[F, FooService[F], β0]})#Λ] =
    _root_.cats.tagless.Derive.readerT[FooService, F]

  implicit val FooServiceFunctorK: _root_.cats.tagless.FunctorK[FooService] = _root_.cats.tagless.Derive.functorK[FooService]

}
