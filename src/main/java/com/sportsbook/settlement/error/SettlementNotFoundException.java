package com.sportsbook.settlement.error;

import java.util.UUID;

/** A settlement resource (bet or materialized match result) was not found — maps to HTTP 404. */
public class SettlementNotFoundException extends RuntimeException {

  public SettlementNotFoundException(String resource, UUID id) {
    super(resource + " " + id + " not found");
  }
}
