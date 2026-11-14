package com.notes.service.web.error;

/**
 * Unlike NoteNotFoundException, this is for a caller who CAN already see the note
 * (read access confirmed) but is attempting something beyond what they're granted -
 * writing without WRITE, or resharing without being the owner. Existence isn't a
 * secret from this caller anymore, so this maps to a real 403, not 404.
 */
public class InsufficientPermissionException extends RuntimeException {
    public InsufficientPermissionException(String message) {
        super(message);
    }
}
