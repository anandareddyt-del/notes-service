package com.notes.service.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Check;

/**
 * One access grant on a note, to either a user or a team - never both, enforced by the
 * check constraint below. Modeled as two nullable foreign keys (userPrincipal /
 * teamPrincipal) rather than a single polymorphic (principalType, principalId) column
 * pair: a single generic id column can't have a real FK constraint, since it would need
 * to be valid against either the user or team table depending on a type tag, and the
 * database can't enforce that. Two real FKs mean Postgres/H2 itself guarantees every
 * grant points at a real user or team - no dangling ACL rows are possible, not just
 * "shouldn't happen if the app behaves."
 */
@Entity
@Table(
    name = "note_acl",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"note_id", "user_principal_id"}),
        @UniqueConstraint(columnNames = {"note_id", "team_principal_id"})
    }
)
@Check(constraints = "(user_principal_id IS NOT NULL) <> (team_principal_id IS NOT NULL)")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NoteAcl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "note_id", nullable = false)
    private Note note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_principal_id")
    private User userPrincipal;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_principal_id")
    private Team teamPrincipal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PermissionLevel permission;
}
