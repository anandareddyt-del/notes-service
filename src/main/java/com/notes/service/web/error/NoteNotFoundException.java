package com.notes.service.web.error;

/**
 * Thrown both when a note truly doesn't exist and when the caller has zero access to
 * it - deliberately the same exception for both cases. Mapping this to 404 either way
 * means a caller with no access can't distinguish "doesn't exist" from "exists but you
 * can't see it," which is the point: returning 403 instead would confirm existence to
 * someone who shouldn't even know to ask.
 */
public class NoteNotFoundException extends RuntimeException {
    public NoteNotFoundException(Long noteId) {
        super("Note not found: " + noteId);
    }
}
