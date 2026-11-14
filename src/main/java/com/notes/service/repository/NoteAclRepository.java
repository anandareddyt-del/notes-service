package com.notes.service.repository;

import com.notes.service.domain.NoteAcl;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NoteAclRepository extends JpaRepository<NoteAcl, Long> {

    /**
     * Loaded as a full List, not reduced to a single "best permission" row in SQL.
     * Safe because grants on one note are bounded by how many principals that single
     * note was shared with - small, by the "several small teams" scale assumption -
     * not by system-wide note or user count. Keeps the access-check logic (owner vs.
     * direct vs. team-derived, taking the max permission) in one readable place
     * (NoteAccessService) instead of a harder-to-verify SQL aggregate.
     */
    List<NoteAcl> findByNote_Id(Long noteId);

    Optional<NoteAcl> findByNote_IdAndUserPrincipal_Id(Long noteId, Long userId);
}
