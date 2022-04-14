import sbt._

case class CatsEffectAxis(idSuffix: String, directorySuffix: String) extends VirtualAxis.WeakAxis

object ConfigAxes {
  val CatsEffect2 = CatsEffectAxis("_ce2", "-ce2")
  val CatsEffect3 = CatsEffectAxis("_ce3", "-ce3")
}
