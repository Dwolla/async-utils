#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
IFS=$'\n\t'

GENERATED_SOURCES="src/main/scala/example/thrift"

set -x

SCROOGE_VERSION=$1

cd input
find "${GENERATED_SOURCES}" -name "*.scala" -delete
mkdir -p project
echo sbt.version=1.6.2 > project/build.properties
cat << __EOF__ > project/plugins.sbt
addSbtPlugin("com.twitter" % "scrooge-sbt-plugin" % "${SCROOGE_VERSION}")
__EOF__

cat << __EOF__ > build.sbt
scalaVersion := "2.13.8"
libraryDependencies ++= {
  val finagleV = "${SCROOGE_VERSION}"

  Seq(
    "com.twitter" %% "scrooge-core" % finagleV,
    "com.twitter" %% "finagle-thrift" % finagleV,
    "org.apache.thrift" % "libthrift" % "0.10.0",
  )
}
(Compile / scroogeBuildOptions) += com.twitter.scrooge.backend.WithFinagle
(Compile / scroogeThriftOutputFolder) := file("src/main/scala")
__EOF__

sbt scroogeGen
find "../output/${GENERATED_SOURCES}" -name "*.scala" -delete
cp "${GENERATED_SOURCES}"/* "../output/${GENERATED_SOURCES}/"
find src/main/scala -name "*.scala" -print0 | \
  xargs -0 -n1 \
    sed -i '' -e '1 i\
/*rule = AddCatsTaglessInstances*/
'
rm -rf build.sbt project

set +x

WHITE_BACKGROUND="\e[47m"
NORMAL_TEXT="\e[0m"
BOLD_TEXT="\e[1m"
RED_TEXT="\e[31m"
GREEN_TEXT="\e[32m"

cat << __EOF__
Now edit $(echo -e "${BOLD_TEXT}${RED_TEXT}")scalafix/output/"${GENERATED_SOURCES}"/SimpleService.scala$(echo -e "${NORMAL_TEXT}") and replace the
blank line at the end of the SimpleService object with these lines:$(echo -e "${WHITE_BACKGROUND}${GREEN_TEXT}")


  implicit def SimpleServiceInReaderT[F[_]]: SimpleService[({type Λ[β0] = _root_.cats.data.ReaderT[F, SimpleService[F], β0]})#Λ] =
    _root_.cats.tagless.Derive.readerT[SimpleService, F]

  implicit val SimpleServiceFunctorK: _root_.cats.tagless.FunctorK[SimpleService] = _root_.cats.tagless.Derive.functorK[SimpleService]

$(echo -e "${NORMAL_TEXT}")
__EOF__
