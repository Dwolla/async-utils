package com.dwolla.util.async.http4s

import cats.effect._
import munit.CatsEffectSuite

class DrainSignalSpec extends CatsEffectSuite {
  test("a new signal isn't draining") {
    DrainSignal[IO].flatMap(_.isDraining).assertEquals(false)
  }

  test("startDraining makes isDraining true") {
    for {
      signal <- DrainSignal[IO]
      _ <- signal.startDraining
      draining <- signal.isDraining
    } yield assert(draining)
  }

  test("startDraining twice is fine and stays draining") {
    for {
      signal <- DrainSignal[IO]
      _ <- signal.startDraining.replicateA_(2)
      draining <- signal.isDraining
    } yield assert(draining)
  }
}
