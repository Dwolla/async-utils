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
ThisBuild / tlBaseVersion := "1.1"
ThisBuild / githubWorkflowScalaVersions := Seq("2.13", "2.12")
ThisBuild / tlCiReleaseBranches := Seq("main")
ThisBuild / mergifyRequiredJobs ++= Seq("validate-steward")
ThisBuild / mergifyStewardConfig ~= { _.map(_.copy(
  author = "dwolla-oss-scala-steward[bot]",
  mergeMinors = true,
))}
ThisBuild / tlJdkRelease := Option(8)
ThisBuild / tlFatalWarnings := githubIsWorkflowBuild.value

lazy val `async-utils-root` = (project in file("."))
  .aggregate(allProjects.map(_.project) *)
  .settings(
    publish / skip := true,
    publishArtifact := false,
  )
  .enablePlugins(AsyncUtilsBuildPlugin)
