package com.sportsbook.settlement.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.settlement.error.DependencyUnavailableException;
import com.sportsbook.settlement.infrastructure.id.UuidV7;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.web.client.RestClient;

/** Response-translation coverage for {@link WalletCreditClient} against a WireMock wallet stub. */
class WalletCreditClientTest {

  private static final String CREDIT_PATH = "/internal/v1/wallet/transactions/credit";
  private static final UUID BET = UuidV7.generate();
  private static final UUID USER = UuidV7.generate();

  private static WireMockServer wm;

  @BeforeAll
  static void startServer() {
    wm = new WireMockServer(options().dynamicPort());
    wm.start();
  }

  @AfterAll
  static void stopServer() {
    wm.stop();
  }

  @BeforeEach
  void reset() {
    wm.resetAll();
  }

  private WalletCreditClient client(Duration readTimeout) {
    ClientHttpRequestFactorySettings settings =
        ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(Duration.ofMillis(300))
            .withReadTimeout(readTimeout);
    RestClient http =
        RestClient.builder()
            .baseUrl(wm.baseUrl())
            .requestFactory(ClientHttpRequestFactories.get(settings))
            .build();
    return new WalletCreditClient(http);
  }

  @Test
  @DisplayName("200 -> returns op id, forwards the idempotency key + source")
  void creditSuccess() {
    UUID operationGroupId = UuidV7.generate();
    wm.stubFor(
        post(urlEqualTo(CREDIT_PATH))
            .willReturn(okJson("{\"operationGroupId\":\"" + operationGroupId + "\"}")));

    UUID result =
        client(Duration.ofMillis(500))
            .credit("settle:payout:" + BET, USER, Money.krw(5_000), CreditSource.HOUSE_POOL);

    assertThat(result).isEqualTo(operationGroupId);
    wm.verify(
        postRequestedFor(urlEqualTo(CREDIT_PATH))
            .withHeader("Idempotency-Key", equalTo("settle:payout:" + BET))
            .withRequestBody(matchingJsonPath("$.source", equalTo("HOUSE_POOL"))));
  }

  @Test
  @DisplayName("5xx -> DependencyUnavailableException (retry / DLQ signal)")
  void serverError() {
    wm.stubFor(post(urlEqualTo(CREDIT_PATH)).willReturn(aResponse().withStatus(503)));

    assertThatThrownBy(
            () ->
                client(Duration.ofMillis(500))
                    .credit(
                        "settle:refund:" + BET, USER, Money.krw(5_000), CreditSource.USER_LOCKED))
        .isInstanceOf(DependencyUnavailableException.class);
  }

  @Test
  @DisplayName("read timeout -> DependencyUnavailableException")
  void timeout() {
    wm.stubFor(
        post(urlEqualTo(CREDIT_PATH))
            .willReturn(
                okJson("{\"operationGroupId\":\"" + UuidV7.generate() + "\"}")
                    .withFixedDelay(1_000)));

    assertThatThrownBy(
            () ->
                client(Duration.ofMillis(150))
                    .credit(
                        "settle:refund:" + BET, USER, Money.krw(5_000), CreditSource.USER_LOCKED))
        .isInstanceOf(DependencyUnavailableException.class);
  }
}
