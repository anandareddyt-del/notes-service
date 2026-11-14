package com.notes.service.service;

import com.notes.service.domain.PermissionLevel;
import com.notes.service.domain.User;
import com.notes.service.repository.UserRepository;
import com.notes.service.web.error.InsufficientPermissionException;
import com.notes.service.web.error.NoteNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against the real (H2, in-memory) persistence layer rather than mocks -
 * deliberately, because the behavior under test (Hibernate's optimistic-lock WHERE
 * clause, the unique/check constraints on note_acl) only exists at the database layer;
 * a mocked repository would just prove the mock does what we told it to, not that the
 * mechanism actually works. No @Transactional on this class - each NoteService call
 * commits independently, which is required for the second write below to see a truly
 * stale version instead of reading from a shared, rolled-back test transaction.
 */
@SpringBootTest
class NoteServiceIntegrationTest {

    @Autowired
    private NoteService noteService;
    @Autowired
    private UserRepository userRepository;

    @Test
    void secondWriteWithStaleVersion_isRejected_firstWritersChangeIsNotLost() {
        User owner = userRepository.save(User.builder().email("owner@example.com").displayName("Owner").build());
        var note = noteService.createNote(owner.getId(), "Title", "Body v1");
        long originalVersion = note.getVersion();

        var afterFirstWrite = noteService.updateNote(note.getId(), owner.getId(), "Title", "Body v2", originalVersion);
        assertThat(afterFirstWrite.getVersion()).isEqualTo(originalVersion + 1);

        // Second writer still acting on the version they originally read - must be
        // rejected, never silently merged or overwritten (the no-lost-updates invariant).
        assertThatThrownBy(() ->
            noteService.updateNote(note.getId(), owner.getId(), "Title", "Conflicting body", originalVersion)
        ).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        var current = noteService.getForRead(note.getId(), owner.getId());
        assertThat(current.getBody()).isEqualTo("Body v2");
    }

    @Test
    void sharingGrantsAccess_andOnlyTheOwnerMayGrantIt() {
        User owner = userRepository.save(User.builder().email("owner2@example.com").displayName("Owner2").build());
        User collaborator = userRepository.save(User.builder().email("collab@example.com").displayName("Collab").build());
        User stranger = userRepository.save(User.builder().email("stranger@example.com").displayName("Stranger").build());
        var note = noteService.createNote(owner.getId(), "Title", "Body");

        // Zero access -> 404-mapped exception, not a 403-mapped one (no existence leakage)
        assertThatThrownBy(() -> noteService.getForRead(note.getId(), stranger.getId()))
            .isInstanceOf(NoteNotFoundException.class);

        noteService.shareWithUser(note.getId(), owner.getId(), collaborator.getId(), PermissionLevel.WRITE);
        assertThat(noteService.getForRead(note.getId(), collaborator.getId()).getId()).isEqualTo(note.getId());

        // Collaborator has WRITE on content but can never reshare - no privilege escalation
        assertThatThrownBy(() ->
            noteService.shareWithUser(note.getId(), collaborator.getId(), stranger.getId(), PermissionLevel.READ)
        ).isInstanceOf(InsufficientPermissionException.class);
    }
}
