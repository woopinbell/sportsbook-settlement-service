package com.sportsbook.settlement.api;

import com.sportsbook.protocol.error.ErrorCode;
import com.sportsbook.protocol.error.ProblemDetail;
import com.sportsbook.settlement.error.SettlementConflictException;
import com.sportsbook.settlement.error.SettlementNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Renders settlement errors as RFC 7807 {@code application/problem+json} (ADR-0004), reusing the
 * shared-protocol {@link ProblemDetail} shape. NOT_FOUND / CONFLICT are not in the shared {@link
 * ErrorCode} catalog (it seeds only betting rejection codes), so they are built inline; binding
 * failures map to VALIDATION_FAILED and anything else to INTERNAL_ERROR. {@code correlationId} is
 * the current trace id from the logging MDC.
 */
@RestControllerAdvice
public class SettlementExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(SettlementExceptionHandler.class);
  private static final URI NOT_FOUND_TYPE = URI.create("https://sportsbook/errors/not-found");
  private static final URI CONFLICT_TYPE = URI.create("https://sportsbook/errors/conflict");

  @ExceptionHandler(SettlementNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleNotFound(
      SettlementNotFoundException e, HttpServletRequest request) {
    return build(
        HttpStatus.NOT_FOUND, NOT_FOUND_TYPE, "Not found", "NOT_FOUND", e.getMessage(), request);
  }

  @ExceptionHandler(SettlementConflictException.class)
  public ResponseEntity<ProblemDetail> handleConflict(
      SettlementConflictException e, HttpServletRequest request) {
    return build(
        HttpStatus.CONFLICT, CONFLICT_TYPE, "Conflict", "CONFLICT", e.getMessage(), request);
  }

  @ExceptionHandler({MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
  public ResponseEntity<ProblemDetail> handleBadRequest(Exception e, HttpServletRequest request) {
    return problem(ErrorCode.VALIDATION_FAILED, e.getMessage(), request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception e, HttpServletRequest request) {
    log.error("Unhandled error on {}", request.getRequestURI(), e);
    return problem(ErrorCode.INTERNAL_ERROR, "Internal server error", request);
  }

  // Six args, but each a distinct value of the RFC 7807 body — a holder would not clarify the call.
  @SuppressWarnings("checkstyle:ParameterNumber")
  private static ResponseEntity<ProblemDetail> build(
      HttpStatus status,
      URI type,
      String title,
      String code,
      String detail,
      HttpServletRequest request) {
    ProblemDetail body =
        new ProblemDetail(
            type,
            title,
            status.value(),
            code,
            detail,
            URI.create(request.getRequestURI()),
            MDC.get("traceId"));
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }

  private static ResponseEntity<ProblemDetail> problem(
      ErrorCode code, String detail, HttpServletRequest request) {
    ProblemDetail body =
        code.toProblemDetail(detail, URI.create(request.getRequestURI()), MDC.get("traceId"));
    return ResponseEntity.status(code.httpStatus())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
