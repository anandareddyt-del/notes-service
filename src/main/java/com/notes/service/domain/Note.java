package com.notes.service.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * A note has exactly one owner and no bidirectional collection of its ACL grants here
 * (no {@code List<NoteAcl> acl} field) - access checks go through NoteAclRepository with
 * a targeted query for the specific note+principal being checked, not by loading every
 * grant a note has into memory. A note shared with a 50-person team would otherwise mean
 * fetching 50 rows just to answer "can user X write to this note," when the real
 * question only needs a single indexed existence check.
 *
 * {@code version} is Hibernate's built-in optimistic-locking column: every UPDATE is
 * compiled as {@code UPDATE notes SET ..., version = version + 1 WHERE id = ? AND
 * version = ?}, and a stale write throws OptimisticLockException, mapped to 409 by
 * GlobalExceptionHandler. This is the mechanism behind the concurrency-control design
 * choice - see README.
 */
@Entity
@Table(name = "note")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Note {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private User owner;

    @Column(nullable = false)
    private String title;

    @Lob
    @Column(nullable = false)
    private String body;

    @Version
    private Long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;
}
