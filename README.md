# Async Utilities

[![Dwolla/async-utils CI](https://github.com/Dwolla/async-utils/actions/workflows/ci.yml/badge.svg)](https://github.com/Dwolla/async-utils/actions)
[![license](https://img.shields.io/github/license/Dwolla/async-utils.svg)](https://github.com/Dwolla/async-utils/blob/main/LICENSE.txt)
[![GitHub release (latest SemVer)](https://img.shields.io/github/v/release/dwolla/async-utils)](https://github.com/Dwolla/async-utils/releases)

## Use `ReaderT` for safely converting `Future`-based traits to cats-effect

You have a higher-kinded trait like this:

```scala
trait FooService[F[_]] {
  def foo(i: Int): F[Unit]
}
```

and an implementation in `Future`:

```scala
class FutureFoo extends FooService[Future] {
  def foo(i: Int): Future[Unit] = Future(println(i))
}
```

(Perhaps the implementation was automatically generated by a tool like [Twitter Scrooge](https://github.com/twitter/scrooge), in which case, scroll down for Twitter Future and Finagle support!)

With cats-tagless, you can auto-derive a `FunctorK[FooService]` instance and an implementation in `ReaderT[Future, FooService[Future], *]`:

```scala
object FooService {
  import cats.tagless._

  implicit def FooServiceReaderT[F[_]]: FooService[ReaderT[F, FooService[F], *]] = 
    Derive.readerT[FooService, F]
  implicit val FooServiceFunctorK: FunctorK[FooService] = Derive.functorK
}
```

These implicit instances can be automatically added to the code generated by Scrooge by running the `AddCatsTaglessInstances` scalafix rule! See below for more detail.

Now you can safely convert your `Future`-based implementation into one in `F[_] : Async`:

```scala
import cats.tagless.syntax.all._
import com.dwolla.util.async.stdlib._

val futureFoo: FooService[Future] = new FutureFoo

val fooService: FooService[IO] = futureFoo.asyncMapK[IO]
```

## Artifacts

The Group ID for each artifact is `"com.dwolla"`. All artifacts are published to Maven Central.

<table>
<thead>
<tr>
<th>Artifact</th>
<th>Description</th>
<th align="center">Scala 2.12</th>
<th align="center">Scala 2.13</th>
<th align="center">Scala.js</th>
</tr>
</thead>
<tbody>
<tr>
<td><code>"async-utils-core"</code></td>
<td>Shared definition of <code>AsyncFunctorK</code> and supporting code</td>
<td align="center"><g-emoji class="g-emoji" alias="white_check_mark" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/2705.png">✅</g-emoji></td>
<td align="center"><g-emoji class="g-emoji" alias="white_check_mark" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/2705.png">✅</g-emoji></td>
<td align="center"><g-emoji class="g-emoji" alias="white_check_mark" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/2705.png">✅</g-emoji></td>
</tr>
<tr>
<td><code>"async-utils"</code></td>
<td>Implementation for stdlib Scala <code>Future</code></td>
<td align="center"><g-emoji class="g-emoji" alias="white_check_mark" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/2705.png">✅</g-emoji></td>
<td align="center"><g-emoji class="g-emoji" alias="white_check_mark" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/2705.png">✅</g-emoji></td>
<td align="center"><g-emoji class="g-emoji" alias="white_check_mark" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/2705.png">✅</g-emoji></td>
</tr>
<tr>
<td><code>"async-utils-twitter"</code></td>
<td>Implementation for Twitter <code>Future</code></td>
<td align="center"><g-emoji class="g-emoji" alias="white_check_mark" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/2705.png">✅</g-emoji></td>
<td align="center"><g-emoji class="g-emoji" alias="white_check_mark" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/2705.png">✅</g-emoji></td>
<td align="center"><g-emoji class="g-emoji" alias="x" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/274c.png">❌</g-emoji></td>
</tr>
<tr>
<td><code>"async-utils-finagle"</code></td>
<td>Safely create Thrift clients and servers using cats-effect as the effect type</td>
<td align="center"><g-emoji class="g-emoji" alias="white_check_mark" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/2705.png">✅</g-emoji></td>
<td align="center"><g-emoji class="g-emoji" alias="white_check_mark" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/2705.png">✅</g-emoji></td>
<td align="center"><g-emoji class="g-emoji" alias="x" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/274c.png">❌</g-emoji></td>
</tr>
<tr>
<td><code>"async-utils-finagle-natchez"</code></td>
<td>Bridge between Natchez tracing and Finagle's built-in Zipkin support</td>
<td align="center"><g-emoji class="g-emoji" alias="white_check_mark" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/2705.png">✅</g-emoji></td>
<td align="center"><g-emoji class="g-emoji" alias="white_check_mark" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/2705.png">✅</g-emoji></td>
<td align="center"><g-emoji class="g-emoji" alias="x" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/274c.png">❌</g-emoji></td>
</tr>
<tr>
<td><code>"finagle-tagless-scalafix"</code></td>
<td>Automatically adds implicit instances needed by `asyncMapK` to the companion objects of Finagle services generated by Scrooge</td>
<td align="center"><g-emoji class="g-emoji" alias="white_check_mark" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/2705.png">✅</g-emoji></td>
<td align="center"><g-emoji class="g-emoji" alias="white_check_mark" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/2705.png">✅</g-emoji></td>
<td align="center"><g-emoji class="g-emoji" alias="x" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/274c.png">❌</g-emoji></td>
</tr>
<tr>
<td><code>"async-utils-log4cats"</code></td>
<td>Semiautomatically instrument tagless algebras with input/output logging using <code>cats.Show</code></td>
<td align="center"><g-emoji class="g-emoji" alias="white_check_mark" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/2705.png">✅</g-emoji></td>
<td align="center"><g-emoji class="g-emoji" alias="white_check_mark" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/2705.png">✅</g-emoji></td>
<td align="center"><g-emoji class="g-emoji" alias="white_check_mark" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/2705.png">✅</g-emoji></td>
</tr>
</tbody>
</table>

## Twitter Ecosystem Releases
Twitter uses a `{YEAR}-{MONTH}` version scheme, where the combination of the two forms a major version.

The "current" version of the artifacts published by this project intend to track
the latest Twitter ecosystem release, with previously supported versions published
as supplemental artifacts with the supported release version appended to
the artifact name.

For example, the latest Twitter ecosystem version is `22.7.0`, so the latest version of
`com.dwolla::async-utils-twitter` depends on `com.twitter::util-core:22.7.0`. In addition,
we publish artifacts named like `com.dwolla::async-utils-twitter-22.4.0` for each of the
previously supported Twitter ecosystem releases.

## Twitter Futures

```scala
import cats.data.ReaderT
import cats.tagless.{Derive, FunctorK}
import com.twitter.util.Closable

// generated by twitter-scrooge
trait FooScroogeService[F[_]] {
  def foo(i: Int): F[Unit]

  def asClosable: Closable = Closable.nop
}

object FooScroogeService {
  // Let Scalafix generate these instances for you! 
  // Follow the instructions in the `Scalafix Rule` section below.
  implicit def FooScroogeServiceReaderT[F[_]]: FooScroogeService[ReaderT[F, FooScroogeService[F], *]] =
    Derive.readerT[FooScroogeService, F]
  implicit val FooScroogeServiceFunctorK: FunctorK[FooScroogeService] = Derive.functorK[FooScroogeService]
}
```

### Finagle Clients

Safely create a Finagle client in `IO` from an implementation in Twitter Future:

```scala
import com.dwolla.util.async.finagle.ThriftClient
import com.dwolla.util.async.twitter._

val fooClient: Resource[IO, FooScroogeService[IO]] = ThriftClient[FooScroogeService]("destination")
```

### Finagle Servers

Safely create a Finagle server in Twitter Future from an implementation in `IO`:

```scala
import com.dwolla.util.async.finagle.ThriftServer
import com.dwolla.util.async.twitter._

val fooImpl: FooScroogeService[IO] = new FooScroogeService[IO] {
  def foo(i: Int): IO[Unit] = IO(println(i))
}

val thriftServer: IO[Nothing] = ThriftServer("address", fooImpl)
```

## Scalafix Rule

Add Scalafix to your project's build by [following the instructions](https://scalacenter.github.io/scalafix/docs/users/installation.html#sbt):

1. Add the Scalafix plugin to the project by adding this to `project/plugins.sbt`:

    ```scala
    addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.11.0")
    ```

2. Enable SemanticDB by adding this to `build.sbt`:

    ```scala
    ThisBuild / semanticdbEnabled := true
    ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
    ThisBuild / scalafixScalaBinaryVersion := CrossVersion.binaryScalaVersion(scalaVersion.value)
    ThisBuild / scalafixDependencies += "com.dwolla" %% "finagle-tagless-scalafix" % "0.2.0"
    ```

3. Run the Scalafix rule automatically after generating the Thrift sources by adding this to `build.sbt`:

    ```scala
    Compile / scalafix / unmanagedSources := (Compile / sources).value
    Compile / compile := Def.taskDyn {
      val compileOutput = (Compile / compile).value

      Def.task {
        (Compile / scalafix).toTask(" AddCatsTaglessInstances").value
        compileOutput
      }
    }.value
    libraryDependencies ++= {
      val catsTaglessV = "0.14.0"
      Seq(
        "org.typelevel" %% "cats-tagless-core" % catsTaglessV,
        "org.typelevel" %% "cats-tagless-macros" % catsTaglessV,
      )
    }
    ```

### `AddCatsTaglessInstances`

The `AddCatsTaglessInstances` rule finds generated Thrift service traits and adds implicit instances of
`ThriftService[Kleisli[F, ThriftService[Future], *]]` and `FunctorK[ThriftService]` to each service's
companion object.

Twitter's Scrooge project changed the way it generates code for Thrift services, removing the
higher-kinded service trait used by this library, leaving only the `MethodPerEndpoint` trait
that used to extend the higher-kinded service trait, setting the type parameter to `com.twitter.util.Future`.
The `AddCatsTaglessInstances` rule now addresses this as well, rewriting `MethodPerEndpoint` to
`{Name}Service` and reintroducing the type parameter. (A new `MethodPerEndpoint` is also added, 
going back to how it used to `extend {Name}Service[Future]`.)

This Scalafix rule should be idempotent, so it can be rerun many times.

### `AdaptHigherKindedThriftCode`

Because the `AddCatsTaglessInstances` rewrite rule couldn't easily move the new `{Name}Service` trait up
to the same level as the `{Name}Service` object, the new traits must be addressed differently. In other
words, instead of finding the trait at `com.example.ThriftService`, it will now be 
at `com.example.ThriftService.ThriftService`.

The `AdaptHigherKindedThriftCode` rule exists to adapt existing code to the new location. It will
find references to traits that extend `com.twitter.finagle.thrift.ThriftService` and have a type 
parameter of the correct shape, and add the object name before the trait name (i.e., rewriting 
`ThriftService` to `ThriftService.ThriftService` or `com.example.ThriftService` to
`com.example.ThriftService.ThriftService`).

This rule is not idempotent, but it will typically only be executed once per codebase.

The order in which the rule is executed matters. Follow these steps:

1. Add Scalafix to your project by following steps 1 and 2 under "Scalafix Rule" above.
2. Look at your project's sbt project graph. Because the rule is a semantic rule, it depends 
   on the compiler being able to compile the code it will modify. This means the leaves of
   the project graph need to be updated before the nodes that depend on each leaf.
   
   For example, run `Test/scalafix AdaptHigherKindedThriftCode` before 
   running `Compile/scalafix AdaptHigherKindedThriftCode`.
3. Only after running the `AdaptHigherKindedThriftCode` rule should you update the Scrooge
   and Finagle version being used in the project. Once this is updated, you can run the 
   `AddCatsTaglessInstances` rule on the updated generated code.

## `async-utils-log4cats`

Given a tagless algebra:

```scala
trait MyAlgebra[F[_]] {
  def foo(foo: Int, bar: String): F[Boolean]
}
```

ensure it has an `Aspect[MyAlgebra, Show, Show]` instance:

```scala
object MyAlgebra {
  implicit val showInt: Show[Int] = Show.fromToString
  implicit val showString: Show[String] = Show.show[String](identity)
  implicit val showBoolean: Show[Boolean] = Show.fromToString
  
  implicit val loggingMyAlgebraAspect: Aspect[MyAlgebra, Show, Show] = Derive.aspect
} 
```

Then, in a scope where you have an effect `F[_] : MonadCancelThrow : Logger`, enable
logging by using the `withMethodLogging` transformation method on an instance of the algebra.

```scala
import cats.*
import cats.effect.*
import cats.tagless.*
import cats.tagless.aop.*
import com.dwolla.util.tagless.logging.*
import org.typelevel.log4cats.slf4j.Slf4jFactory

object MyApp extends IOApp.Simple {
  private val fakeMyAlgebra: MyAlgebra[IO] = new MyAlgebra[IO] {
    override def foo(foo: Int, bar: String): IO[Boolean] = IO.pure(true)
  }

  override def run: IO[Unit] =
    Slf4jFactory.create[IO].create.flatMap { implicit logger =>
      fakeMyAlgebra
        .withMethodLogging
        .foo(42, "The Answer to the Ultimate Question of Life, the Universe, and Everything")
        .flatMap(IO.println)
    }
}
```

Running this results in something like this being printed to the console:

```
2024-04-24 14:26:41,281 | log_level=INFO  'MyAlgebra.foo(foo=42, bar=The Answer to the Ultimate Question of Life, the Universe, and Everything) returning true'
true
```

The first line comes from the logging subsystem, which was logback via slf4j. 
The second line comes from the `IO.println` in `MyApp`.

## Credits

Thanks to [Georgi Krastev and the cats-tagless project](https://github.com/typelevel/cats-tagless/pull/250/files) for the idea to use `ReaderT` in this way.
