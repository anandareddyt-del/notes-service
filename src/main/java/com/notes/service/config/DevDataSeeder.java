package com.notes.service.config;

import com.notes.service.domain.Team;
import com.notes.service.domain.TeamMembership;
import com.notes.service.domain.User;
import com.notes.service.repository.TeamMembershipRepository;
import com.notes.service.repository.TeamRepository;
import com.notes.service.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds two demo users and a team so the README's curl examples work without any
 * extra setup step. Deliberately NOT an H2-console-based approach - an exposed,
 * interactive database console is exactly the kind of thing a vulnerability scanner
 * flags (H2's console allows arbitrary SQL/Java execution), so it's never enabled
 * here, in any profile, rather than enabled-but-"only for dev." @Profile("!postgres")
 * keeps this out of anything pointed at a real database.
 */
@Component
@Profile("!postgres")
public class DevDataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final TeamMembershipRepository teamMembershipRepository;

    public DevDataSeeder(UserRepository userRepository, TeamRepository teamRepository,
                          TeamMembershipRepository teamMembershipRepository) {
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
        this.teamMembershipRepository = teamMembershipRepository;
    }

    @Override
    public void run(String... args) {
        // Idempotent: multiple Spring context configurations (e.g. across different
        // test classes) can share the same named in-memory H2 instance within one JVM
        // run, each triggering this runner again - without this guard, the second
        // context start fails on the unique email constraint instead of being a no-op.
        if (userRepository.count() > 0) {
            return;
        }
        User alice = userRepository.save(User.builder().email("alice@example.com").displayName("Alice").build());
        User bob = userRepository.save(User.builder().email("bob@example.com").displayName("Bob").build());
        Team engineering = teamRepository.save(Team.builder().name("Engineering").build());
        teamMembershipRepository.save(TeamMembership.builder().team(engineering).user(bob).build());
    }
}
