package com.sportsbook.settlement.error;

/**
 * An operation conflicts with the bet's current state — e.g. an admin void of an already SETTLED /
 * VOIDED bet. Maps to HTTP 409.
 */
public class SettlementConflictException extends RuntimeException {

  public SettlementConflictException(String message) {
    super(message);
  }
}
