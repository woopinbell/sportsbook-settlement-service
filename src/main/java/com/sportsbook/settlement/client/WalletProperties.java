package com.sportsbook.settlement.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Endpoint + timeouts for the synchronous wallet credit (payout / refund), bound from {@code
 * settlement.wallet.*}. Settlement is not on the user's request path, so the read timeout is looser
 * than betting's placement timeout; a slow wallet still fails fast into a retry rather than
 * stalling a consumer thread indefinitely.
 */
@ConfigurationProperties(prefix = "settlement.wallet")
public record WalletProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

  private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofMillis(200);
  private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(1);

  public WalletProperties {
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = "http://localhost:8081";
    }
    if (connectTimeout == null) {
      connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    }
    if (readTimeout == null) {
      readTimeout = DEFAULT_READ_TIMEOUT;
    }
  }
}
