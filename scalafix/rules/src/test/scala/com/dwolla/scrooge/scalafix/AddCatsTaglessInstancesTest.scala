package com.dwolla.scrooge.scalafix

import com.eed3si9n.expecty.Expecty.expect
import munit.FunSuite
import scalafix.Patch

import scala.meta._

class AddCatsTaglessInstancesTest extends FunSuite {

  private val input =
    f"""object SimpleService extends _root_.com.twitter.finagle.thrift.GeneratedThriftService { self =>
       |  trait MethodPerEndpoint extends _root_.com.twitter.finagle.thrift.ThriftService {
       |
       |    def makeRequest(request: example.thrift.SimpleRequest): Future[example.thrift.SimpleResponse]
       |    /**
       |     * Used to close the underlying `Service`.
       |     * Not a user-defined API.
       |     */
       |    def asClosable: _root_.com.twitter.util.Closable = _root_.com.twitter.util.Closable.nop
       |  }
       |}
       |
       |// this section simulates an input that has already run the scalafix once, to ensure that the rules don't accumulate during each compile
       |object SimpleService2 extends _root_.com.twitter.finagle.thrift.GeneratedThriftService { self =>
       |  trait SimpleService2[F[_]] extends _root_.com.twitter.finagle.thrift.ThriftService {
       |    def makeRequest(request: example.thrift.SimpleRequest): F[example.thrift.SimpleResponse]
       |    /**
       |     * Used to close the underlying `Service`.
       |     * Not a user-defined API.
       |     */
       |    def asClosable: _root_.com.twitter.util.Closable = _root_.com.twitter.util.Closable.nop
       |  }
       |
       |  object SimpleService2 {
       |    implicit def SimpleService2InReaderT[F[_]]: SimpleService2[({type Λ[β0] = _root_.cats.data.ReaderT[F, SimpleService2[F], β0]})#Λ] =
       |      _root_.cats.tagless.Derive.readerT[SimpleService2, F]
       |
       |    implicit val SimpleService2FunctorK: _root_.cats.tagless.FunctorK[SimpleService2] = _root_.cats.tagless.Derive.functorK[SimpleService2]
       |  }
       |
       |  trait MethodPerEndpoint extends SimpleService2[Future]
       |}
       |""".stripMargin

  private val tree: Tree = input.parse[Source].get

  private val patches = AddCatsTaglessInstances.addServiceTrait(tree)

  private val patch = Patch.fromIterable(patches)
  
  private def range(str: String)
                   (f: String => Int): String =
    s"${f(str)}..${f(str) + str.length}"

  test("rename MethodPerEndpoint to SimpleService[F[_]]") {
    val expectedPatchContent =
      s"Add(MethodPerEndpoint, MethodPerEndpoint [${range("MethodPerEndpoint")(input.indexOf)}), SimpleService[F[_]])"

    expect(patch.toString.contains(expectedPatchContent))
  }

  test("replace Future[*] with F[*]") {
    val expectedPatchContent =
      s"Add(Future, Future [${range("Future")(input.indexOf)}), F)"

    expect(patch.toString.contains(expectedPatchContent))
  }

  test("add SimpleService companion object with implicits") {
    val expectedPatchContent =
      """Add(}, } [467..468), }
        |
        |  object SimpleService {
        |    implicit def SimpleServiceInReaderT[F[_]]: SimpleService[({type Λ[β0] = _root_.cats.data.ReaderT[F, SimpleService[F], β0]})#Λ] =
        |      _root_.cats.tagless.Derive.readerT[SimpleService, F]
        |
        |    implicit val SimpleServiceFunctorK: _root_.cats.tagless.FunctorK[SimpleService] = _root_.cats.tagless.Derive.functorK[SimpleService]
        |  }
        |)""".stripMargin

    expect(patch.toString.contains(expectedPatchContent))
  }

  test("add replacement MethodPerEndpoint that extends SimpleService[Future]") {
    val expectedPatchContent =
      """Add(}, } [467..468), }
        |  trait MethodPerEndpoint extends SimpleService[Future])""".stripMargin

    expect(patch.toString.contains(expectedPatchContent))
  }
  
  test("the patches don't touch SimpleService2, which has already been fixed") {
    expect(!patch.toString.contains("SimpleService2"))
    expect(!patch.toString.contains(s"Add(Future, Future [${range("Future")(input.lastIndexOf)}), F)"))
  }
}
