package com.notes.service.service;

import com.notes.service.domain.*;
import com.notes.service.repository.*;
import com.notes.service.web.error.InsufficientPermissionException;
import com.notes.service.web.error.NoteNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the note lifecycle and is the one place that wires NoteAccessService's
 * permission checks to the actual read/write/share operations. Transactional at the
 * class level (not just on writes) - even the read paths need a consistent view across
 * the note fetch and the ACL/team-membership lookups inside NoteAccessService, so a
 * concurrent ACL change can't be observed half-applied mid-check.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);

    private final NoteRepository noteRepository;
    private final NoteAclRepository noteAclRepository;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final NoteAccessService accessService;

    public Note createNote(Long ownerId, String title, String body) {
        User owner = userRepository.findById(ownerId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown user: " + ownerId));
        Note note = Note.builder().owner(owner).title(title).body(body).build();
        Note saved = noteRepository.save(note);
        log.info("note created id={} ownerId={}", saved.getId(), ownerId);
        return saved;
    }

    @Transactional(readOnly = true)
    public Note getForRead(Long noteId, Long callerId) {
        Note note = noteRepository.findById(noteId)
            .orElseThrow(() -> new NoteNotFoundException(noteId));
        if (!accessService.canRead(note, callerId)) {
            // Same exception as "doesn't exist" - see NoteNotFoundException for why.
            throw new NoteNotFoundException(noteId);
        }
        return note;
    }

    @Transactional(readOnly = true)
    public Page<Note> listVisibleToUser(Long callerId, java.util.Set<Long> callerTeamIds, Pageable pageable) {
        return noteRepository.findVisibleToUser(callerId, callerTeamIds, pageable);
    }

    /**
     * expectedVersion is the version the client last saw. We overwrite the freshly
     * fetched entity's version with it before save(): on flush, Hibernate compiles
     * UPDATE ... SET version = version + 1 WHERE id = ? AND version = ?, using
     * whatever value is currently on the entity - which is now the client's belief,
     * not necessarily the true current value. If another writer already moved the
     * real version past expectedVersion, that WHERE clause matches zero rows and
     * Hibernate throws ObjectOptimisticLockingFailureException, mapped to 409 by
     * GlobalExceptionHandler. This is the whole concurrency-control mechanism from
     * the design doc, expressed in four lines.
     */
    public Note updateNote(Long noteId, Long callerId, String title, String body, long expectedVersion) {
        Note note = noteRepository.findById(noteId)
            .orElseThrow(() -> new NoteNotFoundException(noteId));

        if (!accessService.canRead(note, callerId)) {
            throw new NoteNotFoundException(noteId);
        }
        if (!accessService.canWrite(note, callerId)) {
            throw new InsufficientPermissionException(
                "Caller " + callerId + " does not have write access to note " + noteId);
        }

        note.setTitle(title);
        note.setBody(body);
        note.setVersion(expectedVersion);
        Note saved = noteRepository.save(note);
        log.info("note updated id={} callerId={} newVersion={}", noteId, callerId, saved.getVersion());
        return saved;
    }

    /**
     * Owner-only, by design: this is the "no privilege escalation" invariant from the
     * sharing-model pillar - a WRITE collaborator can edit content but can never grant
     * access to anyone else, only the owner can.
     */
    public NoteAcl shareWithUser(Long noteId, Long callerId, Long granteeUserId, PermissionLevel permission) {
        Note note = requireOwner(noteId, callerId);
        User grantee = userRepository.findById(granteeUserId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown user: " + granteeUserId));
        NoteAcl acl = NoteAcl.builder().note(note).userPrincipal(grantee).permission(permission).build();
        NoteAcl saved = noteAclRepository.save(acl);
        log.info("note shared id={} withUserId={} permission={}", noteId, granteeUserId, permission);
        return saved;
    }

    public NoteAcl shareWithTeam(Long noteId, Long callerId, Long teamId, PermissionLevel permission) {
        Note note = requireOwner(noteId, callerId);
        Team team = teamRepository.findById(teamId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown team: " + teamId));
        NoteAcl acl = NoteAcl.builder().note(note).teamPrincipal(team).permission(permission).build();
        NoteAcl saved = noteAclRepository.save(acl);
        log.info("note shared id={} withTeamId={} permission={}", noteId, teamId, permission);
        return saved;
    }

    private Note requireOwner(Long noteId, Long callerId) {
        Note note = noteRepository.findById(noteId)
            .orElseThrow(() -> new NoteNotFoundException(noteId));
        if (!accessService.canRead(note, callerId)) {
            throw new NoteNotFoundException(noteId);
        }
        if (!accessService.isOwner(note, callerId)) {
            throw new InsufficientPermissionException(
                "Only the owner may reshare note " + noteId);
        }
        return note;
    }
}
