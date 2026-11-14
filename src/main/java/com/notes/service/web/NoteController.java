package com.notes.service.web;

import com.notes.service.domain.Note;
import com.notes.service.domain.PermissionLevel;
import com.notes.service.repository.TeamMembershipRepository;
import com.notes.service.service.NoteService;
import com.notes.service.web.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Set;

/**
 * /api/v1 path prefix: breaking changes get a new version, additive changes stay
 * within v1 - see README. No X-User-Id auth here would be a real JWT/session principal
 * in production (see README's "what I'd change" section); it's a stub so the sharing
 * and concurrency logic can be exercised end-to-end without spending the remaining
 * time budget on a full auth stack.
 */
@RestController
@RequestMapping("/api/v1/notes")
@RequiredArgsConstructor
public class NoteController {

    private final NoteService noteService;
    private final TeamMembershipRepository teamMembershipRepository;

    @PostMapping
    public ResponseEntity<NoteResponse> create(
        @RequestHeader("X-User-Id") Long callerId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody CreateNoteRequest request
    ) {
        Note note = noteService.createNote(callerId, request.title(), request.body(), idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/notes/" + note.getId()))
            .body(NoteResponse.from(note));
    }

    @GetMapping("/{id}")
    public NoteResponse get(@RequestHeader("X-User-Id") Long callerId, @PathVariable Long id) {
        return NoteResponse.from(noteService.getForRead(id, callerId));
    }

    @GetMapping
    public Page<NoteResponse> list(@RequestHeader("X-User-Id") Long callerId, Pageable pageable) {
        Set<Long> callerTeamIds = teamMembershipRepository.findTeamIdsByUserId(callerId);
        return noteService.listVisibleToUser(callerId, callerTeamIds, pageable).map(NoteResponse::from);
    }

    @PutMapping("/{id}")
    public NoteResponse update(
        @RequestHeader("X-User-Id") Long callerId,
        @PathVariable Long id,
        @Valid @RequestBody UpdateNoteRequest request
    ) {
        Note updated = noteService.updateNote(id, callerId, request.title(), request.body(), request.version());
        return NoteResponse.from(updated);
    }

    @PostMapping("/{id}/shares")
    @ResponseStatus(HttpStatus.CREATED)
    public void share(
        @RequestHeader("X-User-Id") Long callerId,
        @PathVariable Long id,
        @Valid @RequestBody ShareNoteRequest request
    ) {
        PermissionLevel permission = request.permission();
        if (request.principalType() == ShareNoteRequest.PrincipalType.USER) {
            noteService.shareWithUser(id, callerId, request.principalId(), permission);
        } else {
            noteService.shareWithTeam(id, callerId, request.principalId(), permission);
        }
    }
}
