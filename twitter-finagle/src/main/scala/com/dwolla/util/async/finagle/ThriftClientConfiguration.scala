package com.dwolla.util.async.finagle

import com.twitter.finagle.service.RetryBudget
import com.twitter.util.Duration

case class ThriftClientConfiguration(
                                      requestTimeout: Duration = Duration.fromMinutes(4),
                                      sessionAcquisitionTimeout: Duration = Duration.fromSeconds(30),
                                      transportConnectTimeout: Duration = Duration.fromSeconds(30),
                                      sessionPoolMax: Int = 50,
                                      retryBudget: RetryBudget = RetryBudget.Empty
                                    )
