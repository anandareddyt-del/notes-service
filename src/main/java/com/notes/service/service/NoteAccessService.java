package com.notes.service.service;

import com.notes.service.domain.Note;
import com.notes.service.domain.NoteAcl;
import com.notes.service.domain.PermissionLevel;
import com.notes.service.repository.NoteAclRepository;
import com.notes.service.repository.TeamMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Computes effective access deny-by-default: the only way to get a permission back is
 * an explicit match (owner, direct grant, or team-derived grant) - there is no code
 * path here that returns access without one of those three matching. This is the
 * single place that logic lives; NoteService calls into this rather than re-deriving
 * access rules per endpoint, so there's exactly one place to get it right.
 */
@Service
@RequiredArgsConstructor
public class NoteAccessService {

    private final NoteAclRepository noteAclRepository;
    private final TeamMembershipRepository teamMembershipRepository;

    /**
     * Owner always resolves to WRITE without a DB round trip for ACL rows - ownership
     * is a column on the note itself (Note.owner), not a grant that could be missing.
     * Otherwise loads this note's (small, bounded) grant list and the caller's team
     * memberships (a Set, for O(1) contains checks below instead of an O(n) scan per
     * grant), and takes the highest permission among matching grants - PermissionLevel
     * is declared READ, WRITE in that order, so max-by-ordinal gives WRITE precedence
     * over READ when a caller somehow holds both (e.g. a direct READ grant plus WRITE
     * via team membership).
     */
    public Optional<PermissionLevel> effectivePermission(Note note, Long callerId) {
        if (note.getOwner().getId().equals(callerId)) {
            return Optional.of(PermissionLevel.WRITE);
        }

        Set<Long> callerTeamIds = teamMembershipRepository.findTeamIdsByUserId(callerId);
        List<NoteAcl> grants = noteAclRepository.findByNote_Id(note.getId());

        return grants.stream()
            .filter(grant -> matches(grant, callerId, callerTeamIds))
            .map(NoteAcl::getPermission)
            .max(Comparator.comparing(Enum::ordinal));
    }

    public boolean canRead(Note note, Long callerId) {
        return effectivePermission(note, callerId).isPresent();
    }

    public boolean canWrite(Note note, Long callerId) {
        return effectivePermission(note, callerId)
            .filter(p -> p == PermissionLevel.WRITE)
            .isPresent();
    }

    public boolean isOwner(Note note, Long callerId) {
        return note.getOwner().getId().equals(callerId);
    }

    private boolean matches(NoteAcl grant, Long callerId, Set<Long> callerTeamIds) {
        if (grant.getUserPrincipal() != null) {
            return grant.getUserPrincipal().getId().equals(callerId);
        }
        return callerTeamIds.contains(grant.getTeamPrincipal().getId());
    }
}
