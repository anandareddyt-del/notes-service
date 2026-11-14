package com.notes.service.repository;

import com.notes.service.domain.Note;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

public interface NoteRepository extends JpaRepository<Note, Long> {

    /**
     * The three-way OR here is the "effective access" union from the README's design
     * doc: owned, directly shared, or shared via team membership. DISTINCT guards
     * against a note matching twice (e.g. shared with two of the caller's teams) from
     * the LEFT JOIN fan-out. Paginated rather than returning a full List - a user's
     * note count can grow without bound over time, and loading all of them per request
     * doesn't scale with it.
     */
    @Query("""
        SELECT DISTINCT n FROM Note n
        LEFT JOIN NoteAcl a ON a.note = n
        WHERE n.owner.id = :userId
           OR a.userPrincipal.id = :userId
           OR a.teamPrincipal.id IN :teamIds
        ORDER BY n.updatedAt DESC
        """)
    Page<Note> findVisibleToUser(
        @Param("userId") Long userId,
        @Param("teamIds") Collection<Long> teamIds,
        Pageable pageable
    );
}
