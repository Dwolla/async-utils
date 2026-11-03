package com.dwolla.util.async.http4s

import cats.effect._
import cats.syntax.all._
import fs2.Chunk
import fs2.io.net.{Network, Socket}
import org.http4s.server.Server
import org.typelevel.ci._
import scodec.bits.ByteVector

import java.io.IOException
import java.nio.charset.StandardCharsets.US_ASCII
import scala.concurrent.duration._

/**
 * A raw-socket HTTP/1.1 client for tests that need exact control over keep-alive, and need to see what a server
 * does with the connection itself (answer, close, reset, or leave it open) rather than what a pooling client
 * reports.
 */
private[http4s] object RawHttpClient {
  final case class RawResponse(statusLine: String, headers: List[(CIString, String)]) {
    /** Every value of the `name` header, so a duplicated header shows up as a comma-separated list. */
    def header(name: CIString): Option[String] =
      headers.collect { case (`name`, value) => value }.toNel.map(_.mkString_(", "))
  }

  /** What a client saw after sending a request. */
  sealed trait Reply
  object Reply {
    final case class Answered(statusLine: String, connection: Option[String]) extends Reply
    final case class Refused(exception: String) extends Reply
    final case class Reset(exception: String) extends Reply
    case object ClosedWithoutAnswer extends Reply
    case object NoAnswer extends Reply
  }

  sealed trait ConnectionFate
  object ConnectionFate {
    case object ClosedByServer extends ConnectionFate
    final case class ResetByServer(exception: String) extends ConnectionFate
    case object StillOpen extends ConnectionFate
    final case class UnexpectedBytes(bytes: ByteVector) extends ConnectionFate
  }

  /** A keep-alive `GET`: HTTP/1.1 with no `Connection` header. */
  def keepAliveGet(path: String): Chunk[Byte] =
    Chunk.array(s"GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(US_ASCII))

  private val headerTerminator = ByteVector("\r\n\r\n".getBytes(US_ASCII))

  /** A complete response if `received` holds one: the head, plus the `Content-Length` body that follows it. */
  private def parseResponse(received: ByteVector): Option[RawResponse] = {
    val headerEnd = received.indexOfSlice(headerTerminator)
    if (headerEnd < 0) None
    else {
      val lines = new String(received.take(headerEnd).toArray, US_ASCII).split("\r\n").toList
      val headers = lines.drop(1).map { line =>
        val (name, value) = line.span(_ != ':')
        CIString(name) -> value.drop(1).trim
      }
      val response = RawResponse(lines.headOption.getOrElse(""), headers)
      val contentLength = response.header(ci"Content-Length").map(_.toLong).getOrElse(0L)
      if (received.length >= headerEnd + headerTerminator.length + contentLength) response.some
      else None
    }
  }

  private def readResponse(socket: Socket[IO], received: ByteVector): IO[Option[RawResponse]] =
    parseResponse(received) match {
      case Some(response) => response.some.pure[IO]
      case None =>
        socket.read(4096).flatMap {
          case Some(chunk) => readResponse(socket, received ++ chunk.toByteVector)
          case None => none[RawResponse].pure[IO]
        }
    }

  /** Reads one response from `socket`, waiting at most `within` for it. */
  def awaitReply(socket: Socket[IO], within: FiniteDuration): IO[Reply] =
    readResponse(socket, ByteVector.empty)
      .map(_.fold[Reply](Reply.ClosedWithoutAnswer)(response => Reply.Answered(response.statusLine, response.header(ci"Connection"))))
      .timeoutTo(within, Reply.NoAnswer.pure[IO].widen)
      .recover { case e: IOException => Reply.Reset(e.toString) }

  /** Sends `request` on a brand-new connection to `server`. */
  def requestOnNewConnection(server: Server, request: Chunk[Byte], within: FiniteDuration): IO[Reply] =
    Network[IO].connect(server.addressIp4s).attempt.use {
      case Right(socket) => socket.write(request) >> awaitReply(socket, within)
      case Left(e: IOException) => Reply.Refused(e.toString).pure[IO].widen
      case Left(e) => e.raiseError[IO, Reply]
    }

  /**
   * What the server does with a connection within `within`: close it, reset it, or leave it open. A `StillOpen`
   * result means a read on the socket was canceled, so make this the last thing a test does with the socket.
   */
  def connectionFate(socket: Socket[IO], within: FiniteDuration): IO[ConnectionFate] =
    socket.read(1)
      .map[ConnectionFate] {
        case None => ConnectionFate.ClosedByServer
        case Some(bytes) => ConnectionFate.UnexpectedBytes(bytes.toByteVector)
      }
      .timeoutTo(within, ConnectionFate.StillOpen.pure[IO].widen)
      .recover { case e: IOException => ConnectionFate.ResetByServer(e.toString) }
}
