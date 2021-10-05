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

case class ThriftService(alg: Defn.Trait,
                         companion: Option[Defn.Object]) {
  private def allInstances: List[ImplicitInstance] = List(
    ImplicitInstance.AlgebraInKleisli(alg.name.value),
    ImplicitInstance.FunctorK(alg.name.value),
  )

  private def code(instances: List[ImplicitInstance]): String =
    instances.map(_.code).mkString("", "\n", "\n")

  def toPatch: Patch =
    companion match {
      case None =>
        val companionString =
          s"""|
              |object ${alg.name} {
              |${code(allInstances)}
              |}""".stripMargin

        Patch.addRight(alg, companionString)
      case Some(companion) =>
        val algName = alg.name.value

        val existingInstances = companion.collect {
          case ImplicitDefAlgInReaderT(`algName`) => ImplicitInstance.AlgebraInKleisli(algName)
          case ImplicitValFunctorK(`algName`) => ImplicitInstance.FunctorK(algName)
        }.toSet[ImplicitInstance]

        val instancesToAdd = allInstances.filterNot(existingInstances)

        if (instancesToAdd.isEmpty) Patch.empty
        else
          companion.tokens.last match {
            case brace@Token.RightBrace() =>
              Patch.addLeft(brace, code(instancesToAdd))
            case other => Patch.addRight(other,
              s""" {
                 |${code(instancesToAdd)}
                 |}""".stripMargin)
          }
    }
}

sealed trait ImplicitInstance {
  def code: String
}
object ImplicitInstance {
  case class AlgebraInKleisli(name: String) extends ImplicitInstance {
    def code: String =
      s"""  implicit def ${name}InReaderT[F[_]]: $name[({type Λ[β0] = _root_.cats.data.ReaderT[F, $name[F], β0]})#Λ] =
         |    _root_.cats.tagless.Derive.readerT[$name, F]
         |""".stripMargin
  }

  case class FunctorK(name: String) extends ImplicitInstance {
    def code: String =
      s"  implicit val ${name}FunctorK: _root_.cats.tagless.FunctorK[$name] = _root_.cats.tagless.Derive.functorK[$name]"
  }
}

object TraitWithTypeConstructor {
  def unapply(subtree: Tree): Option[Defn.Trait] =
    PartialFunction.condOpt(subtree) {
      case term@Defn.Trait(_, _, TypeParamWithSingleHole(_), _, _) => term
    }
}

object TypeParamWithSingleHole {
  def unapply(list: List[Type.Param]): Option[String] =
    PartialFunction.condOpt(list) {
      case List(Type.Param(_, Type.Name(name), List(_: Type.Param), _, _, _)) => name
    }
}

object SingleTypeParameter {
  def unapply(tree: List[Type.Param]): Option[String] =
    PartialFunction.condOpt(tree) {
      case List(Type.Param(_, Type.Name(name), List(), _, _, _)) => name
    }
}

object ImplicitValFunctorK {
  def unapply(tree: Defn.Val): Option[String] =
    PartialFunction.condOpt(tree) {
      case Defn.Val(List(Mod.Implicit()), _, Some(FunctorK(name)), _) => name
    }
}

object ImplicitDefAlgInReaderT {
  def unapply(tree: Defn.Def): Option[String] =
    PartialFunction.condOpt(tree) {
      case Defn.Def(List(Mod.Implicit()), _, TypeParamWithSingleHole(passedEffect), _, Some(Type.Apply(Type.Name(algName), TypeProjectionOfReaderT(effect, alg))), _) if passedEffect == effect && algName == alg =>
        algName
    }
}

object TypeProjectionOfReaderT {
  def unapply(tree: List[Type]): Option[(String, String)] =
    PartialFunction.condOpt(tree) {
      case List(Type.Project(Type.Refine(_, List(Defn.Type(_, _, SingleTypeParameter(projType), ReaderT(kleisliEffect, algName, outputType)))), _)) if projType == outputType =>
        (kleisliEffect, algName)
    }
}

object ReaderT {
  def unapply(tree: Type.Apply): Option[(String, String, String)] =
    PartialFunction.condOpt(tree) {
      case Type.Apply(Type.Select(_, Type.Name("ReaderT")), List(Type.Name(kleisliEffect), Type.Apply(Type.Name(algName), List(Type.Name(algEffect))), Type.Name(outputType))) if kleisliEffect == algEffect =>
        (kleisliEffect, algName, outputType)
    }
}

object FunctorK {
  def unapply(tree: Type.Apply): Option[String] =
    PartialFunction.condOpt(tree) {
      case Type.Apply(Type.Select(_, Type.Name("FunctorK")), List(Type.Name(name))) => name
    }
}
