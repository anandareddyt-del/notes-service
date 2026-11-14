package com.notes.service.web.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Single choke point for every exception leaving the API - the point made in the
 * "don't print stack traces" requirement: no handler here ever puts an exception
 * message or stack trace into the HTTP response body (ProblemDetail's "detail" field
 * is always a hand-written, safe string, never ex.getMessage() for the catch-all
 * case). The full exception, including stack trace, is logged server-side - see the
 * ERROR log in handleUnexpected - correlated to the client-visible traceId.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NoteNotFoundException.class)
    public ProblemDetail handleNotFound(NoteNotFoundException ex) {
        log.warn("note not found or not visible to caller: {}", ex.getMessage());
        return problem(HttpStatus.NOT_FOUND, "Note not found.");
    }

    @ExceptionHandler(InsufficientPermissionException.class)
    public ProblemDetail handleForbidden(InsufficientPermissionException ex) {
        log.warn("permission denied: {}", ex.getMessage());
        return problem(HttpStatus.FORBIDDEN, "You do not have permission to perform this action.");
    }

    /**
     * Thrown by Hibernate when the version in the UPDATE's WHERE clause no longer
     * matches - see NoteService.updateNote. This is the server-side half of the
     * concurrency-control design choice: the client must re-fetch and retry, the
     * server must never silently retry on their behalf (that would be the exact
     * lost-update bug optimistic locking exists to prevent).
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleConflict(ObjectOptimisticLockingFailureException ex) {
        log.warn("optimistic lock conflict: {}", ex.getMessage());
        return problem(HttpStatus.CONFLICT,
            "This note was modified by someone else since you loaded it. Reload and try again.");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class})
    public ProblemDetail handleBadRequest(Exception ex) {
        log.warn("bad request: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "The request was invalid.");
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ProblemDetail handleIdempotencyKeyReused(IdempotencyKeyReusedException ex) {
        log.warn("idempotency key reused for a different request: {}", ex.getMessage());
        return problem(HttpStatus.UNPROCESSABLE_ENTITY,
            "This Idempotency-Key was already used for a different request.");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("unexpected error handling request", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
    }

    private ProblemDetail problem(HttpStatus status, String safeMessage) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, safeMessage);
        pd.setProperty("traceId", MDC.get("requestId"));
        return pd;
    }
}
