package com.dwolla.util.async.finagle

import cats.syntax.all.*
import com.twitter.finagle.service.RetryBudget
import com.twitter.finagle.stats.{DefaultStatsReceiver, StatsReceiver}
import com.twitter.finagle.tracing.{DefaultTracer, Tracer}
import com.twitter.util.Duration

class ThriftClientConfiguration private(val requestTimeout: Duration,
                                        val sessionAcquisitionTimeout: Duration,
                                        val transportConnectTimeout: Duration,
                                        val sessionPoolMax: Int,
                                        private[finagle] val retryBudgetOverride: Option[RetryBudget],
                                        val tracer: Tracer,
                                        val statsReceiver: StatsReceiver,
                                       ) {
  /**
   * The budget set by `withRetryBudget`. If none was set, each call returns
   * a new default budget, `RetryBudget()`, unconnected to any client.
   */
  @deprecated("Retry budgets are per-client state. ThriftClient gives each client Finagle's default budget unless withRetryBudget sets one.", "1.6.0")
  def retryBudget: RetryBudget = retryBudgetOverride.getOrElse(RetryBudget())

  def withRequestTimeout(x: Duration): ThriftClientConfiguration =
    new ThriftClientConfiguration(x, sessionAcquisitionTimeout, transportConnectTimeout, sessionPoolMax, retryBudgetOverride, tracer, statsReceiver)
  def withSessionAcquisitionTimeout(x: Duration): ThriftClientConfiguration =
    new ThriftClientConfiguration(requestTimeout, x, transportConnectTimeout, sessionPoolMax, retryBudgetOverride, tracer, statsReceiver)
  def withTransportConnectTimeout(x: Duration): ThriftClientConfiguration =
    new ThriftClientConfiguration(requestTimeout, sessionAcquisitionTimeout, x, sessionPoolMax, retryBudgetOverride, tracer, statsReceiver)
  def withSessionPoolMax(x: Int): ThriftClientConfiguration =
    new ThriftClientConfiguration(requestTimeout, sessionAcquisitionTimeout, transportConnectTimeout, x, retryBudgetOverride, tracer, statsReceiver)
  def withRetryBudget(x: RetryBudget): ThriftClientConfiguration =
    new ThriftClientConfiguration(requestTimeout, sessionAcquisitionTimeout, transportConnectTimeout, sessionPoolMax, x.some, tracer, statsReceiver)
  def withTracer(x: Tracer): ThriftClientConfiguration =
    new ThriftClientConfiguration(requestTimeout, sessionAcquisitionTimeout, transportConnectTimeout, sessionPoolMax, retryBudgetOverride, x, statsReceiver)
  def withStatsReceiver(x: StatsReceiver): ThriftClientConfiguration =
    new ThriftClientConfiguration(requestTimeout, sessionAcquisitionTimeout, transportConnectTimeout, sessionPoolMax, retryBudgetOverride, tracer, x)
}

object ThriftClientConfiguration {
  /**
   * The default configuration.
   *
   * The retry budget governs only Finagle's requeues: retries of requests
   * that failed before they were written to the server, such as when a host
   * refuses the connection. The server never received those requests, so
   * requeueing them is safe even for calls that aren't idempotent. With
   * `RetryBudget.Empty`, a refused connection fails the call instead of
   * trying another host.
   *
   * By default, each client built from this configuration gets its own
   * Finagle default budget, `RetryBudget()`: about 20% of requests plus 10
   * per second, over a 10-second window. `withRetryBudget` sets one budget
   * that every client built from that configuration shares.
   */
  def apply(): ThriftClientConfiguration = new ThriftClientConfiguration(
    Duration.fromMinutes(4),
    Duration.fromSeconds(30),
    Duration.fromSeconds(30),
    50,
    None,
    DefaultTracer,
    DefaultStatsReceiver
  )
}
