package com.sportsbook.settlement.client;

import com.sportsbook.protocol.value.Money;
import com.sportsbook.settlement.error.DependencyUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.UUID;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Synchronous wallet credit for settlement payouts and refunds (ADR-0006). Unlike betting's debit
 * on the placement path, a settlement credit has no business-rejection branch: the house funds the
 * payout and the locked bucket already holds the refundable stake, so any non-2xx response or
 * transport failure is treated as a transient outage — {@link DependencyUnavailableException} (the
 * only exception the {@code walletClient} circuit breaker records). The caller retries; exhausted
 * retries route the settlement to the DLQ.
 *
 * <p>Idempotency is the caller's responsibility: the same {@code Idempotency-Key} (derived from the
 * betId + leg) makes a retry a no-op on wallet's side.
 */
@Component
public class WalletCreditClient {

  private static final String CREDIT_PATH = "/internal/v1/wallet/transactions/credit";

  private final RestClient http;

  public WalletCreditClient(RestClient walletRestClient) {
    this.http = walletRestClient;
  }

  /**
   * Credits {@code amount} to the user from {@code source}. Returns wallet's operation group id;
   * throws {@link DependencyUnavailableException} on any failure (recorded by the breaker).
   */
  @CircuitBreaker(name = "walletClient", fallbackMethod = "creditFallback")
  public UUID credit(String idempotencyKey, UUID userId, Money amount, CreditSource source) {
    try {
      WalletOperationResponse response =
          http.post()
              .uri(CREDIT_PATH)
              .header("Idempotency-Key", idempotencyKey)
              .contentType(MediaType.APPLICATION_JSON)
              .body(new WalletCreditRequest(userId, amount, source.name()))
              .retrieve()
              .onStatus(
                  HttpStatusCode::isError,
                  (request, res) -> {
                    throw new DependencyUnavailableException(
                        "wallet credit returned "
                            + res.getStatusCode()
                            + " for key "
                            + idempotencyKey);
                  })
              .body(WalletOperationResponse.class);
      return response == null ? null : response.operationGroupId();
    } catch (DependencyUnavailableException e) {
      throw e;
    } catch (RestClientException e) {
      throw new DependencyUnavailableException("wallet credit failed: " + e.getMessage(), e);
    }
  }

  private UUID creditFallback(
      String idempotencyKey, UUID userId, Money amount, CreditSource source, Throwable t) {
    if (t instanceof DependencyUnavailableException dependencyFailure) {
      throw dependencyFailure;
    }
    throw new DependencyUnavailableException("wallet-service unavailable (circuit open)", t);
  }
}
