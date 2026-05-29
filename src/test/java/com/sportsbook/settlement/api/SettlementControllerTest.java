package com.sportsbook.settlement.api;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sportsbook.protocol.domain.SettlementResult;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.settlement.domain.Bet;
import com.sportsbook.settlement.domain.BetSelection;
import com.sportsbook.settlement.domain.SlipKind;
import com.sportsbook.settlement.persistence.BetRepository;
import com.sportsbook.settlement.service.SettlementService;
import com.sportsbook.settlement.service.SettlementTrigger;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer coverage for {@link SettlementController} + RFC 7807 problem responses (ADR-0004). */
@WebMvcTest(SettlementController.class)
class SettlementControllerTest {

  @Autowired MockMvc mvc;

  @MockBean BetRepository bets;
  @MockBean SettlementService settlement;
  @MockBean SettlementTrigger trigger;

  @Test
  @DisplayName("GET returns the settlement view of a settled bet")
  void getSettled() throws Exception {
    UUID betId = UUID.randomUUID();
    Bet bet = singleBet(betId);
    bet.recordSettled(
        SettlementResult.WON, Money.krw(20_000), Instant.parse("2026-05-29T09:00:00Z"));
    given(bets.findById(betId)).willReturn(Optional.of(bet));

    mvc.perform(get("/internal/v1/settlements/{betId}", betId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SETTLED"))
        .andExpect(jsonPath("$.result").value("WON"))
        .andExpect(jsonPath("$.payout.amount").value(20_000));
  }

  @Test
  @DisplayName("GET unknown bet -> 404 problem+json")
  void getNotFound() throws Exception {
    UUID betId = UUID.randomUUID();
    given(bets.findById(betId)).willReturn(Optional.empty());

    mvc.perform(get("/internal/v1/settlements/{betId}", betId))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
  }

  @Test
  @DisplayName("replay -> 202 when a materialized result exists")
  void replayAccepted() throws Exception {
    UUID eventId = UUID.randomUUID();
    given(trigger.replay(eventId)).willReturn(true);

    mvc.perform(post("/internal/v1/settlements/replay/{eventId}", eventId))
        .andExpect(status().isAccepted());
  }

  @Test
  @DisplayName("replay -> 404 when the event has no materialized result")
  void replayNotFound() throws Exception {
    UUID eventId = UUID.randomUUID();
    given(trigger.replay(eventId)).willReturn(false);

    mvc.perform(post("/internal/v1/settlements/replay/{eventId}", eventId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
  }

  @Test
  @DisplayName("admin void -> 200 when the bet was PENDING")
  void voidOk() throws Exception {
    UUID betId = UUID.randomUUID();
    given(bets.existsById(betId)).willReturn(true);
    given(settlement.voidByAdmin(betId)).willReturn(true);

    mvc.perform(post("/internal/v1/settlements/void/{betId}", betId)).andExpect(status().isOk());
  }

  @Test
  @DisplayName("admin void of a non-PENDING bet -> 409 conflict")
  void voidConflict() throws Exception {
    UUID betId = UUID.randomUUID();
    given(bets.existsById(betId)).willReturn(true);
    given(settlement.voidByAdmin(betId)).willReturn(false);

    mvc.perform(post("/internal/v1/settlements/void/{betId}", betId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
  }

  @Test
  @DisplayName("admin void of an unknown bet -> 404")
  void voidNotFound() throws Exception {
    UUID betId = UUID.randomUUID();
    given(bets.existsById(betId)).willReturn(false);

    mvc.perform(post("/internal/v1/settlements/void/{betId}", betId))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("malformed bet id -> 400 validation problem")
  void malformedId() throws Exception {
    mvc.perform(get("/internal/v1/settlements/{betId}", "not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
  }

  private static Bet singleBet(UUID betId) {
    return Bet.fromPlacement(
        betId,
        UUID.randomUUID(),
        SlipKind.SINGLE,
        null,
        null,
        Money.krw(10_000),
        Instant.parse("2026-05-29T07:00:00Z"),
        List.of(
            BetSelection.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("2.0000"))),
        Instant.parse("2026-05-29T07:00:00Z"));
  }
}
