package dev.kvgateway.gateway;

import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

enum RejectionReason {
  NO_CONFIGURATION,
  ADMISSION_LIMIT,
  BODY_BUDGET,
  NO_HEALTHY_ENDPOINT,
  POOL_UNAVAILABLE,
  POOL_QUEUE_FULL,
  POOL_TIMEOUT,
  POOL_RETIRED,
  POOL_CANCELLED,
  CONNECTION_BUDGET,
  POOL_ACQUIRE_ERROR;

  String label() {
    return name().toLowerCase(Locale.ROOT);
  }

  static RejectionReason acquisition(Throwable cause) {
    if (cause instanceof TimeoutException) return POOL_TIMEOUT;
    if (cause instanceof CancellationException) return POOL_CANCELLED;
    if (cause instanceof IllegalStateException) {
      if ("Too many outstanding acquire operations".equals(cause.getMessage()))
        return POOL_QUEUE_FULL;
      if ("FixedChannelPool was closed".equals(cause.getMessage())
          || "endpoint retired".equals(cause.getMessage())) return POOL_RETIRED;
      if ("global connection budget exhausted".equals(cause.getMessage())) return CONNECTION_BUDGET;
    }
    return POOL_ACQUIRE_ERROR;
  }
}
