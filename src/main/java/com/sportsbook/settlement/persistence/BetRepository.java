package com.sportsbook.settlement.persistence;

import com.sportsbook.settlement.domain.Bet;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

  /**
   * Locks a single bet row for the settle transaction so two MatchResults for different events of
   * the same multi serialize. No fetch-join (Postgres rejects {@code FOR UPDATE} on the nullable
   * side of an outer join); selections lazy-load within the same transaction.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from Bet b where b.betId = :id")
  Optional<Bet> findForUpdateById(@Param("id") UUID id);

  /**
   * PENDING bets that have at least one selection on {@code eventId} — the settlement trigger's
   * fan-out when a MatchResult / EventLifecycle arrives. Distinct since a bet may have several legs
   * on the same event (not allowed by L2 for multis, but cheap to guard).
   */
  @Query(
      "select distinct b from Bet b join b.selections s "
          + "where s.eventId = :eventId and b.status = com.sportsbook.settlement.domain"
          + ".SettlementStatus.PENDING")
  List<Bet> findPendingByEvent(@Param("eventId") UUID eventId);
}
