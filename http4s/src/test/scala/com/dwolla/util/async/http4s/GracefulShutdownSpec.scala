package com.dwolla.util.async.http4s

import cats.effect._
import cats.effect.testkit.TestControl
import cats.syntax.all._
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.http4s._
import org.http4s.syntax.all._
import org.scalacheck.Gen
import org.scalacheck.effect.PropF
import org.typelevel.ci._

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class GracefulShutdownSpec extends CatsEffectSuite with ScalaCheckEffectSuite {
  private val connection = ci"Connection"

  private val genStatus: Gen[Status] = Gen.oneOf(Status.Ok, Status.NoContent, Status.Found, Status.BadRequest, Status.NotFound, Status.InternalServerError, Status.ServiceUnavailable)

  // Includes Connection so that every property also covers apps that set it themselves.
  private val genHeader: Gen[Header.Raw] =
    for {
      name <- Gen.oneOf(ci"X-Request-Id", ci"Content-Type", ci"Cache-Control", connection)
      value <- Gen.oneOf("close", "keep-alive", "upgrade", "abc123")
    } yield Header.Raw(name, value)

  private val genHeaders: Gen[List[Header.Raw]] = Gen.listOf(genHeader)

  private val genDrainPeriod: Gen[FiniteDuration] = Gen.choose(1, 3600).map(_.seconds)

  private def appRespondingWith(status: Status, headers: List[Header.Raw]): HttpApp[IO] =
    HttpApp.pure(Response[IO](status).withHeaders(Headers(headers)))

  private def get(path: Uri): Request[IO] = Request[IO](Method.GET, path)

  private def connectionValues(response: Response[IO]): List[String] =
    response.headers.get(connection).toList.flatMap(_.toList).map(_.value)

  private def headersOtherThanConnection(headers: Headers): List[Header.Raw] =
    headers.headers.filterNot(_.name == connection)

  private case class Boom() extends RuntimeException("the server didn't start") with NoStackTrace

  test("health is 204 while serving and 503 while draining") {
    for {
      signal <- DrainSignal[IO]
      routes = GracefulShutdown.healthRoutes(signal)
      serving <- routes.orNotFound.run(get(uri"/health"))
      _ <- signal.startDraining
      draining <- routes.orNotFound.run(get(uri"/health"))
    } yield {
      assertEquals(serving.status, Status.NoContent)
      assertEquals(draining.status, Status.ServiceUnavailable)
    }
  }

  test("health doesn't match other paths") {
    DrainSignal[IO]
      .flatMap(GracefulShutdown.healthRoutes(_).run(get(uri"/other")).value)
      .assertEquals(None)
  }

  test("responses are unchanged until draining starts") {
    PropF.forAllF(genStatus, genHeaders) { (status, headers) =>
      for {
        signal <- DrainSignal[IO]
        response <- GracefulShutdown.closeConnectionsWhileDraining(signal)(appRespondingWith(status, headers)).run(get(uri"/"))
      } yield {
        assertEquals(response.status, status)
        assertEquals(response.headers.headers, headers)
      }
    }
  }

  test("while draining every response carries exactly Connection: close") {
    PropF.forAllF(genStatus, genHeaders) { (status, headers) =>
      for {
        signal <- DrainSignal[IO]
        _ <- signal.startDraining
        response <- GracefulShutdown.closeConnectionsWhileDraining(signal)(appRespondingWith(status, headers)).run(get(uri"/"))
      } yield {
        assertEquals(response.status, status)
        assertEquals(connectionValues(response), List("close"))
        assertEquals(headersOtherThanConnection(response.headers), headersOtherThanConnection(Headers(headers)))
      }
    }
  }

  test("while draining a 404 carries Connection: close") {
    for {
      signal <- DrainSignal[IO]
      _ <- signal.startDraining
      response <- GracefulShutdown.closeConnectionsWhileDraining(signal)(GracefulShutdown.healthRoutes(signal).orNotFound).run(get(uri"/other"))
    } yield {
      assertEquals(response.status, Status.NotFound)
      assertEquals(connectionValues(response), List("close"))
    }
  }

  test("an app's Connection: keep-alive is replaced by close while draining") {
    for {
      signal <- DrainSignal[IO]
      _ <- signal.startDraining
      response <- GracefulShutdown.closeConnectionsWhileDraining(signal)(appRespondingWith(Status.Ok, List(Header.Raw(connection, "keep-alive")))).run(get(uri"/"))
    } yield assertEquals(connectionValues(response), List("close"))
  }

  test("release starts draining at once and releases the server after the drain period") {
    PropF.forAllF(genDrainPeriod) { drainPeriod =>
      for {
        signal <- DrainSignal[IO]
        released <- Ref.of[IO, Boolean](false)
        control <- TestControl.execute(
          GracefulShutdown.drainBeforeRelease(signal, drainPeriod)(Resource.onFinalize(released.set(true))).use_
        )
        _ <- control.tick
        drainingAtOnce <- signal.isDraining
        releasedAtOnce <- released.get
        _ <- control.advance(drainPeriod - 1.nano)
        _ <- control.tick
        releasedJustBeforeDrainPeriod <- released.get
        _ <- control.advance(1.nano)
        _ <- control.tick
        releasedAfterDrainPeriod <- released.get
        outcome <- control.results
      } yield {
        assert(drainingAtOnce, "draining should start at t = 0")
        assert(!releasedAtOnce, "the server shouldn't be released at t = 0")
        assert(!releasedJustBeforeDrainPeriod, "the server shouldn't be released before the drain period elapses")
        assert(releasedAfterDrainPeriod, "the server should be released once the drain period elapses")
        assertEquals(outcome, Some(Outcome.succeeded[cats.Id, Throwable, Unit](())))
      }
    }
  }

  test("a server that fails to start releases without waiting and never starts draining") {
    PropF.forAllF(genDrainPeriod) { drainPeriod =>
      TestControl.executeEmbed {
        for {
          signal <- DrainSignal[IO]
          started <- IO.monotonic
          result <- GracefulShutdown.drainBeforeRelease(signal, drainPeriod)(IO.raiseError[Unit](Boom()).toResource).use_.attempt
          finished <- IO.monotonic
          draining <- signal.isDraining
        } yield (result, finished - started, draining)
      }.assertEquals((Boom().asLeft[Unit], 0.seconds, false))
    }
  }
}
