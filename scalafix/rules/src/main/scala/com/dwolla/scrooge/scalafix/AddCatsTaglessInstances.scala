package com.dwolla.scrooge.scalafix

import scalafix.v1._

import scala.meta._

class AddCatsTaglessInstances extends SemanticRule("AddCatsTaglessInstances") {
  override def fix(implicit doc: SemanticDocument): Patch =
    Patch.fromIterable(addImplicitsToCompanionObjects)

  private def buildThriftServiceFromTrait(alg: Defn.Trait)
                                         (implicit doc: SemanticDocument): ThriftService = {
    val name = alg.name.value

    val companion =
      doc
        .tree
        .collect {
          case obj@Defn.Object(_, Term.Name(`name`), _) => obj
        }
        .headOption

      ThriftService(alg, companion)
  }

  private def addImplicitsToCompanionObjects(implicit doc: SemanticDocument): List[Patch] =
    doc
      .tree
      .collect {
        case TraitWithTypeConstructor(name) => name
      }
      .map(buildThriftServiceFromTrait)
      .map(_.toPatch)
}

object TraitWithTypeConstructor {
  def unapply(subtree: Tree): Option[Defn.Trait] =
    PartialFunction.condOpt(subtree) {
      case term@Defn.Trait(_, _, List(Type.Param(_, _, List(_: Type.Param), _, _, _)), _, _) => term
    }
}

case class ThriftService(alg: Defn.Trait,
                         companion: Option[Defn.Object]) {
  def code: String =
    s"""  implicit def ${alg.name}InReaderT[F[_]]: ${alg.name}[({type Λ[β0] = _root_.cats.data.ReaderT[F, ${alg.name}[F], β0]})#Λ] =
       |    _root_.cats.tagless.Derive.readerT[${alg.name}, F]
       |
       |  implicit val ${alg.name}FunctorK: _root_.cats.tagless.FunctorK[${alg.name}] = _root_.cats.tagless.Derive.functorK[${alg.name}]
       |
       |""".stripMargin

  def toPatch: Patch =
    companion match {
      case None =>
        val companionString =
          s"""|
                  |object ${alg.name} {
              |$code
              |}""".stripMargin

        Patch.addRight(alg, companionString)
      case Some(companion) =>
        companion.tokens.last match {
          case brace@Token.RightBrace() => Patch.addLeft(brace,
            s"""
               |$code
               |""".stripMargin)
          case other => Patch.addRight(other,
            s""" {
               |$code
               |
               |}""".stripMargin)
        }
    }

}
