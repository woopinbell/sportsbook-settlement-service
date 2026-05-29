package com.sportsbook.settlement.client;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the wallet {@link RestClient} (ADR-0006 payout leg). The autoconfigured builder is cloned
 * so the Boot customizations (Micrometer observation, etc.) carry over, then pinned to the wallet
 * base URL and the fail-fast connect / read timeouts.
 */
@Configuration
public class WalletClientConfig {

  @Bean
  RestClient walletRestClient(RestClient.Builder builder, WalletProperties properties) {
    return builder
        .clone()
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory(properties))
        .build();
  }

  private static ClientHttpRequestFactory requestFactory(WalletProperties properties) {
    ClientHttpRequestFactorySettings settings =
        ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(properties.connectTimeout())
            .withReadTimeout(properties.readTimeout());
    return ClientHttpRequestFactories.get(settings);
  }
}
