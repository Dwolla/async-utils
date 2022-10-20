/*rule = AddCatsTaglessInstances*/
/**
 * Generated by Scrooge
 *   version: 22.7.0
 *   rev: 2bb5fe76210bd2837988f9703fc9ca47d51bbe26
 *   built at: 20220728-182510
 */
package example.thrift

import com.twitter.finagle.{service => ctfs}
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
import com.twitter.finagle.thrift.{ClientFunction, Protocols, RichClientParam, ThriftClientRequest}
import com.twitter.util.Future
import org.apache.thrift.TApplicationException
import org.apache.thrift.protocol._


@javax.annotation.Generated(value = Array("com.twitter.scrooge.Compiler"))
class SimpleService$FinagleClient(
    val service: com.twitter.finagle.Service[ThriftClientRequest, Array[Byte]],
    val clientParam: RichClientParam)
  extends SimpleService.MethodPerEndpoint {

  @deprecated("Use com.twitter.finagle.thrift.RichClientParam", "2017-08-16")
  def this(
    service: com.twitter.finagle.Service[ThriftClientRequest, Array[Byte]],
    protocolFactory: TProtocolFactory = Protocols.binaryFactory(),
    serviceName: String = "SimpleService",
    stats: StatsReceiver = NullStatsReceiver,
    responseClassifier: ctfs.ResponseClassifier = ctfs.ResponseClassifier.Default
  ) = this(
    service,
    RichClientParam(
      protocolFactory,
      serviceName,
      clientStats = stats,
      responseClassifier = responseClassifier
    )
  )

  import SimpleService._

  def serviceName: String = clientParam.serviceName

  override def asClosable: _root_.com.twitter.util.Closable = service

  protected def protocolFactory: TProtocolFactory = clientParam.restrictedProtocolFactory

  protected val tlReusableBuffer: _root_.com.twitter.scrooge.TReusableBuffer =
    clientParam.createThriftReusableBuffer()

  protected def decodeResponse[T <: _root_.com.twitter.scrooge.ThriftStruct](
    resBytes: Array[Byte],
    codec: _root_.com.twitter.scrooge.ThriftStructCodec[T]
  ): _root_.com.twitter.util.Try[T] =
    _root_.com.twitter.finagle.thrift.service.ThriftCodec.decodeResponse(resBytes, codec, protocolFactory, serviceName)

  // ----- end boilerplate.

  private[this] def stats: StatsReceiver = clientParam.clientStats
  private[this] def responseClassifier: ctfs.ResponseClassifier = clientParam.responseClassifier

  private[this] val scopedStats: StatsReceiver = if (serviceName != "") stats.scope(serviceName) else stats
  private[this] object __stats_makeRequest {
    val RequestsCounter: _root_.com.twitter.finagle.stats.Counter = scopedStats.scope("MakeRequest").counter("requests")
    val SuccessCounter: _root_.com.twitter.finagle.stats.Counter = scopedStats.scope("MakeRequest").counter("success")
    val FailuresCounter: _root_.com.twitter.finagle.stats.Counter = scopedStats.scope("MakeRequest").counter("failures")
    val FailuresScope: StatsReceiver = scopedStats.scope("MakeRequest").scope("failures")
  }
  val MakeRequestSimpleServiceReplyDeserializer: Array[Byte] => _root_.com.twitter.util.Try[example.thrift.SimpleResponse] = {
    response: Array[Byte] => {
      decodeResponse(response, MakeRequest.Result).flatMap { result: MakeRequest.Result =>
        val firstException = result.firstException()
        if (firstException.isDefined) {
          _root_.com.twitter.util.Throw(_root_.com.twitter.finagle.SourcedException.setServiceName(firstException.get, serviceName))
        } else if (result.successField.isDefined) {
          _root_.com.twitter.util.Return(result.successField.get)
        } else {
          _root_.com.twitter.util.Throw(_root_.com.twitter.scrooge.internal.ApplicationExceptions.missingResult("MakeRequest"))
        }
      }
    }
  }
  
  def makeRequest(request: example.thrift.SimpleRequest): Future[example.thrift.SimpleResponse] =
    ClientFunction.serde[example.thrift.SimpleResponse](
      "MakeRequest",
      MakeRequestSimpleServiceReplyDeserializer,
      MakeRequest.Args(request),
      serviceName,
      service,
      responseClassifier,
      tlReusableBuffer,
      protocolFactory,
      __stats_makeRequest.FailuresScope,
      __stats_makeRequest.RequestsCounter,
      __stats_makeRequest.SuccessCounter,
      __stats_makeRequest.FailuresCounter)
}
