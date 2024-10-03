package com.dwolla.util.async.finagle

import com.twitter.finagle.service.RetryBudget
import com.twitter.finagle.stats.{DefaultStatsReceiver, StatsReceiver}
import com.twitter.finagle.tracing.{DefaultTracer, Tracer}
import com.twitter.util.Duration

class ThriftClientConfiguration private(val requestTimeout: Duration,
                                        val sessionAcquisitionTimeout: Duration,
                                        val transportConnectTimeout: Duration,
                                        val sessionPoolMax: Int,
                                        val retryBudget: RetryBudget,
                                        val tracer: Tracer,
                                        val statsReceiver: StatsReceiver,
                                       ) {
  def withRequestTimeout(x: Duration): ThriftClientConfiguration =
    new ThriftClientConfiguration(x, sessionAcquisitionTimeout, transportConnectTimeout, sessionPoolMax, retryBudget, tracer, statsReceiver)
  def withSessionAcquisitionTimeout(x: Duration): ThriftClientConfiguration =
    new ThriftClientConfiguration(requestTimeout, x, transportConnectTimeout, sessionPoolMax, retryBudget, tracer, statsReceiver)
  def withTransportConnectTimeout(x: Duration): ThriftClientConfiguration =
    new ThriftClientConfiguration(requestTimeout, sessionAcquisitionTimeout, x, sessionPoolMax, retryBudget, tracer, statsReceiver)
  def withSessionPoolMax(x: Int): ThriftClientConfiguration =
    new ThriftClientConfiguration(requestTimeout, sessionAcquisitionTimeout, transportConnectTimeout, x, retryBudget, tracer, statsReceiver)
  def withRetryBudget(x: RetryBudget): ThriftClientConfiguration =
    new ThriftClientConfiguration(requestTimeout, sessionAcquisitionTimeout, transportConnectTimeout, sessionPoolMax, x, tracer, statsReceiver)
  def withTracer(x: Tracer): ThriftClientConfiguration =
    new ThriftClientConfiguration(requestTimeout, sessionAcquisitionTimeout, transportConnectTimeout, sessionPoolMax, retryBudget, x, statsReceiver)
  def withStatsReceiver(x: StatsReceiver): ThriftClientConfiguration =
    new ThriftClientConfiguration(requestTimeout, sessionAcquisitionTimeout, transportConnectTimeout, sessionPoolMax, retryBudget, tracer, x)
}

object ThriftClientConfiguration {
  def apply(): ThriftClientConfiguration = new ThriftClientConfiguration(
    Duration.fromMinutes(4),
    Duration.fromSeconds(30),
    Duration.fromSeconds(30),
    50,
    RetryBudget.Empty,
    DefaultTracer,
    DefaultStatsReceiver
  )
}
