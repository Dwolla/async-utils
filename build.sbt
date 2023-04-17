ThisBuild / organization := "com.dwolla"
ThisBuild / homepage := Some(url("https://github.com/Dwolla/async-utils"))
ThisBuild / licenses := Seq(License.MIT)
ThisBuild / developers := List(
  Developer(
    "bpholt",
    "Brian Holt",
    "bholt+async-utils@dwolla.com",
    url("https://dwolla.com")
  ),
)
ThisBuild / startYear := Option(2021)
ThisBuild / tlSonatypeUseLegacyHost := true
ThisBuild / tlBaseVersion := "1.0"
ThisBuild / githubWorkflowScalaVersions := Seq("2.13", "2.12")
ThisBuild / tlCiReleaseBranches := Seq("main")
ThisBuild / mergifyStewardConfig ~= { _.map(_.copy(
  author = "dwolla-oss-scala-steward[bot]",
  mergeMinors = true,
))}

tpolecatScalacOptions += ScalacOptions.release("8")

lazy val CatsEffect2V = "2.5.5"
lazy val CatsEffect3V = "3.4.9"
lazy val TwitterUtilsLatestV = "22.7.0"
lazy val CatsTaglessV = "0.14.0"
lazy val libthriftV = "0.10.0"

lazy val SCALA_2_13 = "2.13.10"
lazy val SCALA_2_12 = "2.12.17"

lazy val Scala2Versions = Seq(SCALA_2_13, SCALA_2_12)
ThisBuild / scalaVersion := SCALA_2_13

lazy val scala2CompilerPlugins: Seq[ModuleID] = Seq(
  compilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full)
)

lazy val `async-utils-core` = crossProject(JVMPlatform, JSPlatform)
  .in(file("core"))
  .settings(
    crossScalaVersions := Scala2Versions,
    description := "Safely convert final tagless-style algebras implemented in Future to cats-effect Async",
    libraryDependencies ++= {
      Seq(
        "org.typelevel" %% "cats-effect" % CatsEffect3V,
        "org.typelevel" %% "cats-tagless-core" % CatsTaglessV,
      ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
    },
  )
  .jsEnablePlugins(ScalaJSPlugin)

lazy val `async-utils` = crossProject(JVMPlatform, JSPlatform)
  .in(file("scala-futures"))
  .settings(
    crossScalaVersions := Scala2Versions,
    libraryDependencies ++= {
      Seq(
        "org.typelevel" %% "cats-effect" % CatsEffect3V,
      ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
    },
  )
  .jsEnablePlugins(ScalaJSPlugin)
  .dependsOn(`async-utils-core`)

lazy val examples = crossProject(JVMPlatform)
  .in(file("examples"))
  .settings(
    crossScalaVersions := Scala2Versions,
    libraryDependencies ++= {
      Seq(
        "org.typelevel" %% "cats-tagless-macros" % CatsTaglessV,
      ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
    },
    publish / skip := true,
    publishArtifact := false,
  )
  .dependsOn(`async-utils`)

lazy val `async-utils-root` = (project in file("."))
  .aggregate(
    `async-utils-core`.jvm,
    `async-utils-core`.js,
    `async-utils`.jvm,
    `async-utils`.js,
    examples.jvm,
  )
  .settings(
    publish / skip := true,
    publishArtifact := false,
  )
