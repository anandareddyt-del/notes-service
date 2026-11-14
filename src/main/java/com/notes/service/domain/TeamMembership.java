package com.notes.service.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Join row: one user belongs to one team. Modeled as its own entity rather than a
 * {@code @ManyToMany Set<Team>} field on User/Team - with that collection approach,
 * answering "is user X a member of team Y" means loading the whole membership graph
 * into the persistence context first. Here it's a direct, indexed query
 * (TeamMembershipRepository.existsByUserIdAndTeamId) - O(1) lookup instead of an O(n)
 * in-memory scan, and no risk of accidentally pulling a large team's full roster into
 * memory just to answer a single membership check.
 */
@Entity
@Table(
    name = "team_membership",
    uniqueConstraints = @UniqueConstraint(columnNames = {"team_id", "user_id"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeamMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
}
