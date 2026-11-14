package com.notes.service.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** version is the client's last-known version - see NoteService.updateNote. */
public record UpdateNoteRequest(@NotBlank String title, @NotBlank String body, @NotNull Long version) {
}
