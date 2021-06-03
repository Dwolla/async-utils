inThisBuild(List(
  organization := "com.dwolla",
  description := "Safely convert final tagless-style algebras implemented in Future to cats-effect Async",
  homepage := Some(url("https://github.com/Dwolla/")),
  licenses += ("MIT", url("https://opensource.org/licenses/MIT")),
  crossScalaVersions := Seq("2.13.6", "2.12.14"),
  developers := List(
    Developer(
      "bpholt",
      "Brian Holt",
      "bholt+async-utils@dwolla.com",
      url("https://dwolla.com")
    ),
  ),
  startYear := Option(2021),

  githubWorkflowJavaVersions := Seq("adopt@1.8", "adopt@1.11"),
  githubWorkflowTargetTags ++= Seq("v*"),
  githubWorkflowPublishTargetBranches := Seq.empty,
  githubWorkflowPublish := Seq.empty,
))

lazy val CatsEffect2V = "2.5.1"
lazy val CatsEffect3V = "3.1.1"
lazy val TwitterUtilsLatestV = "21.5.0"
lazy val TwitterUtils19_4V = "19.4.0"

lazy val scala2CompilerPlugins: Seq[ModuleID] = Seq(
  compilerPlugin("org.typelevel" % "kind-projector" % "0.13.0" cross CrossVersion.full)
)

def dedupKindProjectorOptions(opts: Seq[String]): Seq[String] =
  if (opts.count(_.contains("-Ykind-projector")) > 1) opts.filterNot(_ == "-Ykind-projector") else opts

lazy val `async-utils-ce2` = (project in file("core-ce2"))
  .settings(
    libraryDependencies ++= {
      Seq(
        "org.typelevel" %% "cats-effect" % CatsEffect2V,
      ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
    },
  )

lazy val `async-utils-ce3` = (project in file("core-ce3"))
  .settings(
    libraryDependencies ++= {
      Seq(
        "org.typelevel" %% "cats-effect" % CatsEffect3V,
      ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
    },
  )

lazy val `async-utils-ce2-twitter` = (project in file("twitter-ce2"))
  .settings(
    libraryDependencies ++= {
      Seq(
        "org.typelevel" %% "cats-effect" % CatsEffect2V,
        "com.twitter" %% "util-core" % TwitterUtilsLatestV,
      ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
    },
  )

lazy val `async-utils-ce2-twitter-19-4` = (project in file("twitter-ce2-19.4"))
  .settings(
    crossScalaVersions := Seq("2.12.14"),
    libraryDependencies ++= {
      Seq(
        "org.typelevel" %% "cats-effect" % CatsEffect2V,
        "com.twitter" %% "util-core" % TwitterUtils19_4V,
      ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
    },
  )

lazy val `async-utils-ce3-twitter` = (project in file("twitter-ce3"))
  .settings(
    libraryDependencies ++= {
      Seq(
        "org.typelevel" %% "cats-effect" % CatsEffect3V,
        "com.twitter" %% "util-core" % TwitterUtilsLatestV,
      ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
    },
  )

lazy val `async-utils-ce3-twitter-19-4` = (project in file("twitter-ce3-19.4"))
  .settings(
    crossScalaVersions := Seq("2.12.14"),
    libraryDependencies ++= {
      Seq(
        "org.typelevel" %% "cats-effect" % CatsEffect3V,
        "com.twitter" %% "util-core" % TwitterUtils19_4V,
      ) ++ (if (scalaVersion.value.startsWith("2")) scala2CompilerPlugins else Nil)
    },
  )

lazy val `async-utils-root` = (project in file("."))
  .aggregate(
    `async-utils-ce2`,
    `async-utils-ce3`,
    `async-utils-ce2-twitter-19-4`,
    `async-utils-ce2-twitter`,
    `async-utils-ce3-twitter-19-4`,
    `async-utils-ce3-twitter`,
  )
