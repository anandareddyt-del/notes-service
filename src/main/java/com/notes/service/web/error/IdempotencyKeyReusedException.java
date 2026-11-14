package com.notes.service.web.error;

/** Same Idempotency-Key reused for a materially different request - a client bug. */
public class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key '" + key + "' was already used for a different request");
    }
}
