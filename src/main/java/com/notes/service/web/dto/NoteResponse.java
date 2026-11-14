package com.notes.service.web.dto;

import com.notes.service.domain.Note;

import java.time.Instant;

public record NoteResponse(Long id, Long ownerId, String title, String body, Long version,
                            Instant createdAt, Instant updatedAt) {
    public static NoteResponse from(Note n) {
        return new NoteResponse(n.getId(), n.getOwner().getId(), n.getTitle(), n.getBody(),
            n.getVersion(), n.getCreatedAt(), n.getUpdatedAt());
    }
}
