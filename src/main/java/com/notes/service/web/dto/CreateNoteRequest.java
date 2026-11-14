package com.notes.service.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateNoteRequest(@NotBlank String title, @NotBlank String body) {
}
