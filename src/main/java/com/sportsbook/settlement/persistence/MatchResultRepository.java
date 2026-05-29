package com.sportsbook.settlement.persistence;

import com.sportsbook.settlement.domain.MatchResultRecord;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the materialized {@link MatchResultRecord} (replay + late-bet settlement). */
public interface MatchResultRepository extends JpaRepository<MatchResultRecord, UUID> {}
