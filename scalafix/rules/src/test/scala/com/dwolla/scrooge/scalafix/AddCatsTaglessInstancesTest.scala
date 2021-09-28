package com.dwolla.scrooge.scalafix

import scalafix.Patch

import scala.meta._

object AddCatsTaglessInstancesTest extends App {

  val input =
    f"""trait SimpleService[F[_]]
       |object SimpleService extends _root_.com.twitter.finagle.thrift.GeneratedThriftService { self =>
       |  val annotations: immutable$$Map[String, String] = immutable$$Map.empty
       |}
       |trait Bar
       |trait Baz[F]
       |trait Boo[+MM[_]]
       |""".stripMargin

  val tree: Tree = input.parse[Source].get

  val symbols: List[Defn.Trait] =
    tree
      .collect {
        case TraitWithTypeConstructor(name) => name
      }

  val thriftServices =
    symbols
      .map { alg =>
        val name = alg.name.value

        val companion =
          tree
            .collect {
              case obj@Defn.Object(_, Term.Name(`name`), _) => obj
            }
            .headOption

        ThriftService(alg, companion)
      }

  val patches = thriftServices.map(_.toPatch)

  println(Patch.fromIterable(patches))
}
