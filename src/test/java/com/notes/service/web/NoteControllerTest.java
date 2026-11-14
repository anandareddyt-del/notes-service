package com.notes.service.web;

import com.notes.service.domain.User;
import com.notes.service.repository.UserRepository;
import com.notes.service.service.NoteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Exercises the actual HTTP boundary (real DispatcherServlet, real JSON
 * (de)serialization, real GlobalExceptionHandler) rather than calling NoteService
 * directly - this is what proves the status-code contract described in the README
 * (404 for no access, 403 once access is already known, 409 on stale version, 400 on
 * invalid input) actually holds over REST, not just in the service layer the earlier
 * tests cover.
 *
 * Deliberately NOT class-level @Transactional: the 409 test needs two genuinely
 * separate transactions (one to write v1, a second to observe it and reject a stale
 * write), the same requirement the fix in NoteServiceIntegrationTest's commit covers -
 * wrapping the whole test method in one transaction would let the second write read
 * the first write's not-yet-flushed in-memory state instead of committed reality.
 * Each @BeforeEach run uses a unique email instead, since nothing rolls back between
 * methods on the shared H2 instance.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NoteControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private NoteService noteService;

    private Long ownerId;
    private Long strangerId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        ownerId = userRepository.save(User.builder().email("owner-" + suffix + "@test.com").displayName("Owner").build()).getId();
        strangerId = userRepository.save(User.builder().email("stranger-" + suffix + "@test.com").displayName("Stranger").build()).getId();
    }

    @Test
    void create_thenGet_returns201ThenNoteBody() throws Exception {
        String body = """
            {"title":"T","body":"B"}""";

        mockMvc.perform(post("/api/v1/notes").header("X-User-Id", ownerId)
                .contentType("application/json").content(body))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.title").value("T"))
            .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void create_withSameIdempotencyKeyAndSameBody_replaysOriginal_doesNotCreateASecondNote() throws Exception {
        String body = """
            {"title":"T","body":"B"}""";
        String key = "retry-key-1";

        String firstResponse = mockMvc.perform(post("/api/v1/notes").header("X-User-Id", ownerId)
                .header("Idempotency-Key", key).contentType("application/json").content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String secondResponse = mockMvc.perform(post("/api/v1/notes").header("X-User-Id", ownerId)
                .header("Idempotency-Key", key).contentType("application/json").content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(secondResponse).isEqualTo(firstResponse);
    }

    @Test
    void create_withSameIdempotencyKeyButDifferentBody_returns422() throws Exception {
        String key = "retry-key-2";
        mockMvc.perform(post("/api/v1/notes").header("X-User-Id", ownerId).header("Idempotency-Key", key)
                .contentType("application/json").content("""
                    {"title":"T1","body":"B1"}"""))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/notes").header("X-User-Id", ownerId).header("Idempotency-Key", key)
                .contentType("application/json").content("""
                    {"title":"T2","body":"B2"}"""))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void create_withBlankTitle_returns400() throws Exception {
        String body = """
            {"title":"","body":"B"}""";

        mockMvc.perform(post("/api/v1/notes").header("X-User-Id", ownerId)
                .contentType("application/json").content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void getNote_withNoAccess_returns404_notFoundOrForbidden() throws Exception {
        var note = noteService.createNote(ownerId, "Private", "secret");

        mockMvc.perform(get("/api/v1/notes/{id}", note.getId()).header("X-User-Id", strangerId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void getNote_thatDoesNotExistAtAll_alsoReturns404_sameAsNoAccess() throws Exception {
        mockMvc.perform(get("/api/v1/notes/{id}", 999999).header("X-User-Id", ownerId))
            .andExpect(status().isNotFound());
    }

    @Test
    void update_byReadOnlyCollaborator_returns403_existenceAlreadyKnown() throws Exception {
        var note = noteService.createNote(ownerId, "T", "B");
        noteService.shareWithUser(note.getId(), ownerId, strangerId, com.notes.service.domain.PermissionLevel.READ);
        String body = """
            {"title":"T2","body":"B2","version":0}""";

        mockMvc.perform(put("/api/v1/notes/{id}", note.getId()).header("X-User-Id", strangerId)
                .contentType("application/json").content(body))
            .andExpect(status().isForbidden());
    }

    @Test
    void update_withStaleVersion_returns409() throws Exception {
        var note = noteService.createNote(ownerId, "T", "B");
        noteService.updateNote(note.getId(), ownerId, "T", "B-v2", 0L); // bumps version to 1

        String staleBody = """
            {"title":"T","body":"conflict","version":0}""";

        mockMvc.perform(put("/api/v1/notes/{id}", note.getId()).header("X-User-Id", ownerId)
                .contentType("application/json").content(staleBody))
            .andExpect(status().isConflict());
    }

    @Test
    void errorResponse_neverContainsRawExceptionDetail() throws Exception {
        mockMvc.perform(get("/api/v1/notes/{id}", 999999).header("X-User-Id", ownerId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Note not found."))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("Exception"))));
    }
}
