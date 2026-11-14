package com.notes.service.service;

import com.notes.service.domain.*;
import com.notes.service.repository.NoteAclRepository;
import com.notes.service.repository.TeamMembershipRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Pure unit test: repositories are mocked, no Spring context, no database - this is
 * testing the deny-by-default permission logic in isolation, which is the point of a
 * unit test versus the heavier service-level integration test alongside it.
 */
@ExtendWith(MockitoExtension.class)
class NoteAccessServiceTest {

    @Mock
    private NoteAclRepository noteAclRepository;
    @Mock
    private TeamMembershipRepository teamMembershipRepository;
    @InjectMocks
    private NoteAccessService accessService;

    private static User user(long id) {
        return User.builder().id(id).email("u" + id + "@example.com").displayName("U" + id).build();
    }

    private static Note noteOwnedBy(User owner) {
        return Note.builder().id(100L).owner(owner).title("t").body("b").version(0L).build();
    }

    @Test
    void ownerAlwaysHasWriteAccess_withoutConsultingAclGrants() {
        User owner = user(1);
        Note note = noteOwnedBy(owner);

        assertThat(accessService.effectivePermission(note, 1L)).contains(PermissionLevel.WRITE);
        assertThat(accessService.isOwner(note, 1L)).isTrue();
    }

    @Test
    void noAccess_whenThereIsNoGrantAtAll_denyByDefault() {
        User owner = user(1);
        Note note = noteOwnedBy(owner);
        when(teamMembershipRepository.findTeamIdsByUserId(2L)).thenReturn(Set.of());
        when(noteAclRepository.findByNote_Id(100L)).thenReturn(List.of());

        assertThat(accessService.effectivePermission(note, 2L)).isEmpty();
        assertThat(accessService.canRead(note, 2L)).isFalse();
    }

    @Test
    void directUserGrant_readOnly_grantsReadButNotWrite() {
        User owner = user(1);
        User collaborator = user(2);
        Note note = noteOwnedBy(owner);
        NoteAcl grant = NoteAcl.builder().note(note).userPrincipal(collaborator).permission(PermissionLevel.READ).build();
        when(teamMembershipRepository.findTeamIdsByUserId(2L)).thenReturn(Set.of());
        when(noteAclRepository.findByNote_Id(100L)).thenReturn(List.of(grant));

        assertThat(accessService.canRead(note, 2L)).isTrue();
        assertThat(accessService.canWrite(note, 2L)).isFalse();
    }

    @Test
    void teamGrant_isInheritedByEveryMemberOfThatTeam_notJustTheGranteeNamedDirectly() {
        User owner = user(1);
        User teamMember = user(3);
        Team engineering = Team.builder().id(7L).name("Engineering").build();
        Note note = noteOwnedBy(owner);
        NoteAcl grant = NoteAcl.builder().note(note).teamPrincipal(engineering).permission(PermissionLevel.WRITE).build();

        // user 3 was never named directly - access comes only through team membership
        when(teamMembershipRepository.findTeamIdsByUserId(3L)).thenReturn(Set.of(7L));
        when(noteAclRepository.findByNote_Id(100L)).thenReturn(List.of(grant));

        assertThat(accessService.canWrite(note, 3L)).isTrue();
    }

    @Test
    void userNotOnTheGrantedTeam_getsNoAccess_evenThoughANeighboringTeamHasAccess() {
        User owner = user(1);
        User outsider = user(4);
        Team engineering = Team.builder().id(7L).name("Engineering").build();
        Note note = noteOwnedBy(owner);
        NoteAcl grant = NoteAcl.builder().note(note).teamPrincipal(engineering).permission(PermissionLevel.WRITE).build();

        when(teamMembershipRepository.findTeamIdsByUserId(4L)).thenReturn(Set.of(9L)); // a different team
        when(noteAclRepository.findByNote_Id(100L)).thenReturn(List.of(grant));

        assertThat(accessService.canRead(note, 4L)).isFalse();
    }
}
