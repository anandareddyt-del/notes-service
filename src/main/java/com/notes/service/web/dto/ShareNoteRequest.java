package com.notes.service.web.dto;

import com.notes.service.domain.PermissionLevel;
import jakarta.validation.constraints.NotNull;

public record ShareNoteRequest(@NotNull PrincipalType principalType, @NotNull Long principalId,
                                @NotNull PermissionLevel permission) {
    public enum PrincipalType { USER, TEAM }
}
