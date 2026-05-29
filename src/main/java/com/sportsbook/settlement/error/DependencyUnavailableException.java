package com.sportsbook.settlement.error;

/**
 * A downstream dependency (wallet-service) is unreachable or failing — timeout, connection error,
 * or 5xx. This is the only exception recorded by the Resilience4j {@code walletClient} circuit
 * breaker (configured in application.yml), so business outcomes never trip the breaker. Raised by
 * the wallet credit client (added in the payout step); declared here so the breaker config resolves
 * at startup.
 */
public class DependencyUnavailableException extends RuntimeException {

  public DependencyUnavailableException(String message) {
    super(message);
  }

  public DependencyUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
