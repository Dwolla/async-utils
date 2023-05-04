ThisBuild / organization := "com.dwolla"
ThisBuild / homepage := Some(url("https://github.com/Dwolla/async-utils-twitter"))
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
ThisBuild / tlBaseVersion := "0.3"
ThisBuild / tlCiReleaseBranches := Seq("main")
ThisBuild / mergifyStewardConfig ~= { _.map(_.copy(
  author = "dwolla-oss-scala-steward[bot]",
  mergeMinors = true,
))}

tpolecatScalacOptions += ScalacOptions.release("8")

lazy val `async-utils-root` = (project in file("."))
  .aggregate(
    allProjects.map(_.project) *
  )
  .settings(
    publish / skip := true,
    publishArtifact := false,
  )
  .enablePlugins(AsyncUtilsTwitterPlugin)
