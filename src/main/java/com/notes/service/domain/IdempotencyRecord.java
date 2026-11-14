package com.notes.service.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row per (caller, idempotency key) pair that has been used to create a note.
 * Scoped to the caller, not global - two different users coincidentally picking the
 * same key string should not collide with each other. requestFingerprint is a plain
 * string, not a cryptographic hash - this isn't a security boundary, just a cheap way
 * to detect "same key, different body" (a client bug) and reject it rather than
 * silently reusing a response for a different request.
 */
@Entity
@Table(
    name = "idempotency_record",
    uniqueConstraints = @UniqueConstraint(columnNames = {"idempotency_key", "caller_id"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "caller_id", nullable = false)
    private Long callerId;

    @Column(nullable = false)
    private String requestFingerprint;

    @Column(nullable = false)
    private Long noteId;
}
