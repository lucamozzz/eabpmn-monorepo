package org.unicam.intermediate.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.identity.Group;
import org.camunda.bpm.engine.identity.User;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.stream.Collectors;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class UserInitializer {

    private final IdentityService identityService;

    @Bean
    @Order(2) // Run after EnvironmentInitializer
    public ApplicationRunner initializeUsers() {
        return args -> {
            log.info("[UserInitializer] Starting user and group initialization...");

            try {
                Group studentsGroup = identityService.createGroupQuery().groupId("students").singleResult();
                if (studentsGroup == null) {
                    studentsGroup = identityService.newGroup("students");
                    studentsGroup.setName("Students");
                    studentsGroup.setType("WORKFLOW");
                    identityService.saveGroup(studentsGroup);
                    log.info("[UserInitializer] Created NEW group: students");
                } else {
                    log.info("[UserInitializer] Group already exists: students");
                }

                // Create or update tutors group
                Group tutorsGroup = identityService.createGroupQuery().groupId("tutors").singleResult();
                if (tutorsGroup == null) {
                    tutorsGroup = identityService.newGroup("tutors");
                    tutorsGroup.setName("Tutors");
                    tutorsGroup.setType("WORKFLOW");
                    identityService.saveGroup(tutorsGroup);
                    log.info("[UserInitializer] Created NEW group: tutors");
                } else {
                    log.info("[UserInitializer] Group already exists: tutors");
                }

                // Create or update Andrea Cataluffi
                User andrea = identityService.createUserQuery().userId("acataluffi").singleResult();
                if (andrea == null) {
                    andrea = identityService.newUser("acataluffi");
                    andrea.setFirstName("Andrea");
                    andrea.setLastName("Cataluffi");
                    andrea.setPassword("a");
                    andrea.setEmail("andrea.cataluffi@studenti.unicam.it");
                    identityService.saveUser(andrea);
                    log.info("[UserInitializer] Created NEW user: acataluffi (Andrea Cataluffi)");
                } else {
                    // Update existing user
                    andrea.setFirstName("Andrea");
                    andrea.setLastName("Cataluffi");
                    andrea.setPassword("password123");
                    andrea.setEmail("andrea.cataluffi@studenti.unicam.it");
                    identityService.saveUser(andrea);
                    log.info("[UserInitializer] UPDATED existing user: acataluffi");
                }

                // Check and add Andrea to students group
                if (!isUserInGroup("acataluffi", "students")) {
                    identityService.createMembership("acataluffi", "students");
                    log.info("[UserInitializer] Added acataluffi to students group");
                } else {
                    log.info("[UserInitializer] acataluffi already in students group");
                }

                // Create or update Luca Mozzoni
                User luca = identityService.createUserQuery().userId("lmozzoni").singleResult();
                if (luca == null) {
                    luca = identityService.newUser("lmozzoni");
                    luca.setFirstName("Luca");
                    luca.setLastName("Mozzoni");
                    luca.setPassword("a");
                    luca.setEmail("luca.mozzoni@unicam.it");
                    identityService.saveUser(luca);
                    log.info("[UserInitializer] Created NEW user: lmozzoni (Luca Mozzoni)");
                } else {
                    // Update existing user
                    luca.setFirstName("Luca");
                    luca.setLastName("Mozzoni");
                    luca.setPassword("tutor456");
                    luca.setEmail("luca.mozzoni@unicam.it");
                    identityService.saveUser(luca);
                    log.info("[UserInitializer] UPDATED existing user: lmozzoni");
                }

                // Check and add Luca to tutors group
                if (!isUserInGroup("lmozzoni", "tutors")) {
                    identityService.createMembership("lmozzoni", "tutors");
                    log.info("[UserInitializer] Added lmozzoni to tutors group");
                } else {
                    log.info("[UserInitializer] lmozzoni already in tutors group");
                }

                // Create admins group if needed
                Group adminGroup = identityService.createGroupQuery().groupId("admins").singleResult();
                if (adminGroup == null) {
                    adminGroup = identityService.newGroup("admins");
                    adminGroup.setName("Administrators");
                    adminGroup.setType("SYSTEM");
                    identityService.saveGroup(adminGroup);
                    log.info("[UserInitializer] Created NEW group: admins");
                } else {
                    log.info("[UserInitializer] Group already exists: admins");
                }

                // Add default admin to admins group if exists and not already member
                User adminUser = identityService.createUserQuery().userId("a").singleResult();
                if (adminUser != null) {
                    if (!isUserInGroup("a", "admins")) {
                        identityService.createMembership("a", "admins");
                        log.info("[UserInitializer] Added user 'a' to admins group");
                    }
                }

                identityService.createMembership("lmozzoni", "admins");
                identityService.createMembership("acataluffi", "admins");

                // Print summary
                log.info("[UserInitializer] ========================================");
                log.info("[UserInitializer] Initialization completed successfully!");
                log.info("[UserInitializer] ========================================");
                log.info("[UserInitializer] Current users count: {}",
                        identityService.createUserQuery().count());
                log.info("[UserInitializer] Current groups count: {}",
                        identityService.createGroupQuery().count());
                log.info("[UserInitializer] ");
                log.info("[UserInitializer] Camunda Cockpit: http://localhost:8082/camunda");
                log.info("[UserInitializer] ");
                log.info("[UserInitializer] Login credentials:");
                log.info("[UserInitializer]   Admin    : a / a");
                log.info("[UserInitializer]   Student  : acataluffi / a");
                log.info("[UserInitializer]   Tutor    : lmozzoni / a");
                log.info("[UserInitializer] ========================================");

                // Debug: List all users and their groups
                log.info("[UserInitializer] All users in system:");
                identityService.createUserQuery().list().forEach(u -> {
                    List<Group> userGroups = identityService.createGroupQuery()
                            .groupMember(u.getId())
                            .list();
                    String groupNames = userGroups.stream()
                            .map(Group::getId)
                            .collect(Collectors.joining(", "));
                    log.info("[UserInitializer]   - {} ({} {}) - Groups: [{}]",
                            u.getId(), u.getFirstName(), u.getLastName(),
                            groupNames.isEmpty() ? "none" : groupNames);
                });

            } catch (Exception e) {
                log.error("[UserInitializer] Failed to initialize users and groups", e);
                e.printStackTrace();
            }
        };
    }

    /**
     * Helper method to check if a user is already member of a group
     */
    private boolean isUserInGroup(String userId, String groupId) {
        try {
            List<Group> userGroups = identityService.createGroupQuery()
                    .groupMember(userId)
                    .list();
            return userGroups.stream().anyMatch(g -> g.getId().equals(groupId));
        } catch (Exception e) {
            log.error("Error checking membership for user {} in group {}: {}",
                    userId, groupId, e.getMessage());
            return false;
        }
    }
}