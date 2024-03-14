package com.dwolla.scrooge.scalafix

import com.dwolla.scrooge.scalafix.AddCatsTaglessInstances.*
import scalafix.v1.*

import scala.meta.*
import scala.meta.tokens.Token.Ident
import GuardedPatchBuilder.toGuardedPatch

import scala.meta.Type.ParamClause

object ThriftServiceTrait {
  def unapply(subtree: Tree): Option[(String, Defn.Trait)] =
    PartialFunction.condOpt(subtree) {
      case term@Defn.Trait.After_4_6_0(_, Type.Name(name), ParamClause(Nil), _, ExtendsThriftService(_)) => (name, term)
    }
}

object ExtendsThriftService {
  def unapply(subtree: Tree): Option[Template] =
    TemplateExtends("ThriftService")(subtree)
}

object ExtendsGeneratedThriftService {
  def unapply(subtree: Tree): Option[Template] =
    TemplateExtends("GeneratedThriftService")(subtree)
}

object InitsContainName {
  def apply(name: String)
           (inits: List[Init]): Option[Init] =
    inits.find {
      _.tpe.collect {
        case Type.Select(_, Type.Name(`name`)) => ()
        case Type.Name(`name`) => ()
      }.nonEmpty
    }
}

object TemplateExtends {
  def apply(name: String): Tree => Option[Template] = subtree => { 
    object TemplateInitsContainName {
      def unapply(inits: List[Init]): Option[Init] =
        InitsContainName(name)(inits)
    }

    PartialFunction.condOpt(subtree) {
      case t@Template.After_4_4_0(_, TemplateInitsContainName(_), _, _, _) => t
    }
  }
}

object AddCatsTaglessInstances {
  def addServiceTrait(tree: Tree): List[Patch] =
    tree
      .collect {
        // find each object that extends the com.twitter.finagle.thrift.GeneratedThriftService interface
        // these are the root objects that contain all the generated traits we need to modify and augment
        case t@Defn.Object(_, _, ExtendsGeneratedThriftService(_)) => t
      }
      .flatMap { companionObjectTree =>
        companionObjectTree
          .collect {
            // find each trait that extends the com.twitter.finagle.thrift.ThriftService interface
            // these are the interfaces we want to modify to add a higher kinded type parameter F[_],
            // and replace Future with the new F
            case ThriftServiceTrait("MethodPerEndpoint", defn) =>
              ThriftServiceTrait(companionObjectTree, defn)
          }
          .flatMap(_.patches)
      }
}

case class ThriftServiceTrait(private val generatedThriftServiceObject: Defn.Object,
                              private val methodPerEndpointTrait: Defn.Trait) {
  private object ExtendsServiceTrait {
    def unapply(subtree: Tree): Option[Template] =
      TemplateExtends(generatedThriftServiceObject.name.value)(subtree)
  }

  private def allInstances: List[ImplicitInstance] = List(
    ImplicitInstance.AlgebraInKleisli(generatedThriftServiceObject.name.value),
    ImplicitInstance.Instrument(generatedThriftServiceObject.name.value),
    ImplicitInstance.HigherKindedToMethodPerEndpoint(generatedThriftServiceObject.name.value),
  )

  private def code(instances: List[ImplicitInstance]): String =
    instances.map(_.code).mkString("", "\n", "")

  private val companionObjectWithImplicits =
    s"""|
        |
        |  object ${generatedThriftServiceObject.name.value} {
        |${code(allInstances)}
        |  }
        |""".stripMargin

  private def renameMethodPerEndpoint(methodPerEndpoint: Type.Name): Patch =
    methodPerEndpoint.tokens.find {
      case Ident("MethodPerEndpoint") => true
      case _ => false
    }
      .map(Patch.replaceToken(_, s"${generatedThriftServiceObject.name.value}[F[_]]"))
      .onlyIfMissing {
        case Defn.Trait.After_4_6_0(_, Type.Name(name), _, _, _) if name == generatedThriftServiceObject.name.value => ()
      }
      .in(generatedThriftServiceObject)

  private val addServiceCompanionObjectWithImplicits: Patch =
    Patch.addRight(methodPerEndpointTrait, companionObjectWithImplicits)
      .onlyIfMissing {
        case Defn.Object(_, Term.Name(name), Template.After_4_4_0(_, List(), _, _, _)) if name == generatedThriftServiceObject.name.value => ()
      }
      .in(generatedThriftServiceObject)

  private val addReplacementMethodPerEndpointTrait: Patch =
    Patch.addRight(methodPerEndpointTrait,
      s"""
         |  trait MethodPerEndpoint extends ${generatedThriftServiceObject.name.value}[Future]""".stripMargin)
      .onlyIfMissing {
        case Defn.Trait.After_4_6_0(_, Type.Name("MethodPerEndpoint"), _, _, ExtendsServiceTrait(_)) => ()
      }
      .in(generatedThriftServiceObject)

  private val modifyMethodPerEndpointTrait: List[Patch] =
    methodPerEndpointTrait
      .collect {
        // rename MethodPerEndpoint to {Name}Service[F[_]], add {Name}Service companion object with
        case t@Type.Name("MethodPerEndpoint") =>
          Patch.fromIterable {
            List(
              renameMethodPerEndpoint(t),
              addServiceCompanionObjectWithImplicits,
              addReplacementMethodPerEndpointTrait,
            )
          }

        // replace Future[*] with F[*]
        case t@Type.Name("Future") =>
          t.parent.fold(Patch.empty) {
            Patch.replaceToken(t.tokens.head, "F")
              .onlyIfMissing {
                case Type.Apply.After_4_6_0(Type.Name(name), _) if name == generatedThriftServiceObject.name.value => ()
              }
              .in(_)
          }
      }

  private val modifyMethodPerEndpointCompanionObject: List[Patch] =
    generatedThriftServiceObject.collect {
      // add new apply method for proxy implementation of MethodPerEndpoint
      case t@Defn.Object(_, Term.Name("MethodPerEndpoint"), _) =>
        t
          .templ
          .tokens
          .find(_.text == "{")
          .map {
            def paramNames(ps: List[Term.Param]): String =
              ps.map(_.name).mkString(", ")

            def invokeEachParamList(paramss: List[List[Term.Param]]): String =
              paramss.map(paramNames).mkString("(", "", ")")

            val methods =
              methodPerEndpointTrait
                .templ
                .collect {
                  case d@Decl.Def.After_4_7_3(_, name, paramss, _) =>
                    s"override ${d.toString()} = fa.$name${invokeEachParamList(paramss.flatMap(_.paramClauses.map(_.values)))}"
                }

            val code =
              s"""
                 |    def apply(fa: ${generatedThriftServiceObject.name.value}[_root_.com.twitter.util.Future]): MethodPerEndpoint = new MethodPerEndpoint {
                 |      ${methods.mkString("", "\n      ", "")}
                 |    }""".stripMargin

            Patch.addRight(_, code)
          }
          .getOrElse(Patch.empty)
    }

  def patches: List[Patch] =
    modifyMethodPerEndpointTrait ++ modifyMethodPerEndpointCompanionObject
}

class GuardedPatch(private val tuple: (Patch, PartialFunction[Tree, ?])) extends AnyVal {
  def in(tree: Tree): Patch = {
    val empty = tree.collect(tuple._2).isEmpty
    if (empty) tuple._1
    else Patch.empty
  }
}

class GuardedPatchBuilder(private val patch: Patch) extends AnyVal {
  def onlyIfMissing(pf: PartialFunction[Tree, ?]) = new GuardedPatch(patch -> pf)
}

object GuardedPatchBuilder {
  implicit def toGuardedPatch(patch: Patch): GuardedPatchBuilder = new GuardedPatchBuilder(patch)
  implicit def toGuardedPatch(patch: Option[Patch]): GuardedPatchBuilder = new GuardedPatchBuilder(patch.getOrElse(Patch.empty))
}

sealed trait IdempotentPatch {
  val patch: Patch
}
case object AlreadyApplied extends IdempotentPatch {
  val patch: Patch = Patch.empty
}
case class Unapplied(patch: Patch) extends IdempotentPatch

object IdempotentPatch {
  def apply(patch: Patch)
           (tree: Tree)
           (pf: PartialFunction[Tree, ?]): IdempotentPatch =
    if (tree.collect(pf).nonEmpty) AlreadyApplied
    else Unapplied(patch)

  def apply(patch: Option[Patch])
           (tree: Tree)
           (pf: PartialFunction[Tree, ?]): IdempotentPatch =
    IdempotentPatch(patch.getOrElse(Patch.empty))(tree)(pf)
}

class AddCatsTaglessInstances extends SemanticRule("AddCatsTaglessInstances") {
  override def fix(implicit doc: SemanticDocument): Patch =
    Patch.fromIterable(addServiceTrait(doc.tree))
}

sealed trait ImplicitInstance {
  def code: String
}
object ImplicitInstance {
  case class AlgebraInKleisli(name: String) extends ImplicitInstance {
    def code: String =
      s"""    implicit def ${name}InReaderT[F[_]]: $name[({type Λ[β0] = _root_.cats.data.ReaderT[F, $name[F], β0]})#Λ] =
         |      _root_.cats.tagless.Derive.readerT[$name, F]
         |""".stripMargin
  }

  case class Instrument(name: String) extends ImplicitInstance {
    // TODO make this Aspect[SimpleService, ToTraceValue, ToTraceValue]
    def code: String =
      s"""    implicit val ${name}Instrument: _root_.cats.tagless.aop.Instrument[$name] = _root_.cats.tagless.Derive.instrument[$name]
         |""".stripMargin
  }
  
  case class HigherKindedToMethodPerEndpoint(name: String) extends ImplicitInstance {
    def code: String =
      s"""    implicit def ${name}HigherKindedToMethodPerEndpoint: _root_.com.dwolla.util.async.finagle.HigherKindedToMethodPerEndpoint[$name] =
         |      new _root_.com.dwolla.util.async.finagle.HigherKindedToMethodPerEndpoint[$name] {
         |        override type MPE = MethodPerEndpoint
         |        override val mpeClassTag: _root_.scala.reflect.ClassTag[MethodPerEndpoint] = _root_.scala.reflect.classTag[MethodPerEndpoint]
         |        override def toMethodPerEndpoint(hk: $name[_root_.com.twitter.util.Future]): MethodPerEndpoint = MethodPerEndpoint(hk)
         |      }
         |""".stripMargin
  }
}
