package com.dwolla.scrooge.scalafix

import com.dwolla.scrooge.scalafix.AdaptHigherKindedThriftCode._
import scalafix.v1
import scalafix.v1._

import scala.meta._

class AdaptHigherKindedThriftCode extends SemanticRule("AdaptHigherKindedThriftCode") {
  override def fix(implicit doc: SemanticDocument): Patch =
    addObjectQualifierForThriftServiceTrait(doc.tree)
}

object AdaptHigherKindedThriftCode {
  private def isGeneratedThriftService(t: Type.Name)
                                      (implicit doc: SemanticDocument): Boolean = {
    val info = t.symbol.info

    val annotatedWithGenerated = info.exists(_.annotations.exists(_.tpe.toString() == "Generated"))

    val anyRef = v1.Symbol("scala/AnyRef#")
    val thriftService = Symbol("com/twitter/finagle/thrift/ThriftService#")

    val extendsThriftServiceAndIsHigherKinded =
      info
        .map(_.signature)
        .collect {

          // class that extends AnyRef and com.twitter.finagle.thrift.ThriftService, and has a single type parameter
          // e.g. FooService[F[_]] extends com.twitter.finagle.thrift.ThriftService
          case ClassSignature(List(singleTypeParameter), List(TypeRef(NoType, `anyRef`, Nil), TypeRef(NoType, `thriftService`, Nil)), _, _) => singleTypeParameter.signature
        }
        .collect {
          // type parameter that has a single hole, e.g. F[_]
          case TypeSignature(List(_), _, _) => true
        }
        .getOrElse(false)

    annotatedWithGenerated && extendsThriftServiceAndIsHigherKinded
  }

  def addObjectQualifierForThriftServiceTrait(tree: Tree)
                                             (implicit doc: SemanticDocument): Patch =
    tree.collect {
      case t@Type.Name(name) if isGeneratedThriftService(t) =>
        Patch.replaceTree(t, s"$name.${t.symbol.normalized.displayName}")
    }
      .fold(Patch.empty)(_ + _)
}
