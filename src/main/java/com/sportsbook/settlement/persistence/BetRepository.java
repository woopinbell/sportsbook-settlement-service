package com.sportsbook.settlement.persistence;

import com.sportsbook.settlement.domain.Bet;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Read-model persistence for the settlement {@link Bet} aggregate. Spring Data derives the CRUD
 * surface; settlement-specific queries (bets touching a resolved event, stale pending scans) are
 * added as the trigger and late-settlement features land.
 */
public interface BetRepository extends JpaRepository<Bet, UUID> {

  /**
   * Loads a bet with its selections eagerly so the resolver (and assertions outside a transaction)
   * can read the legs without a lazy-init round trip.
   */
  @Query("select b from Bet b left join fetch b.selections where b.betId = :id")
  Optional<Bet> findWithSelectionsById(@Param("id") UUID id);
}
