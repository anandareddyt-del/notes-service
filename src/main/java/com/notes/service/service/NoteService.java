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
     * UPDATE ... SET version = version + 1 WHERE id = ? AND version = ? using whatever
     * version was loaded into this persistence context - genuinely concurrent writers
     * are caught by that, unmodified, with no manual intervention needed. The explicit
     * check below handles the far more common case for a request/response API: the
     * client's belief is already stale by the time their request arrives (they read
     * v0, someone else already committed v1, and only now does the client try to save
     * against v0). A fresh findById always returns the true current version, so
     * comparing it to what the client sent catches that case immediately, before any
     * write is attempted - rather than relying on manually forcing the entity's version
     * field to the client's value, which does not reliably drive Hibernate's own
     * WHERE-clause generation for an already-managed entity.
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
        if (!note.getVersion().equals(expectedVersion)) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(Note.class, noteId);
        }

        note.setTitle(title);
        note.setBody(body);
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
