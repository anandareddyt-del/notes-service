package com.notes.service.repository;

import com.notes.service.domain.TeamMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Set;

public interface TeamMembershipRepository extends JpaRepository<TeamMembership, Long> {

    /**
     * Returned as a Set, not a List: callers use this purely for O(1) membership
     * checks (teamIds.contains(x)) while evaluating ACL grants, never for order or
     * duplicates - a Set makes that O(1) contains explicit, where a List would be an
     * O(n) scan per lookup. Small, bounded by how many teams one person is on.
     */
    @Query("SELECT tm.team.id FROM TeamMembership tm WHERE tm.user.id = :userId")
    Set<Long> findTeamIdsByUserId(@Param("userId") Long userId);

    boolean existsByTeam_IdAndUser_Id(Long teamId, Long userId);
}
