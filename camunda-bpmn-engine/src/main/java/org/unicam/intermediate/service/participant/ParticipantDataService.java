package org.unicam.intermediate.service.participant;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.unicam.intermediate.events.ParticipantPositionChangedEvent;
import org.unicam.intermediate.models.pojo.Participant;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Service for managing participant data independently from environment.json.
 * Currently loads static participant data at initialization.
 */
@Service
@Slf4j
@Getter
@Scope("singleton")
public class ParticipantDataService {

    private final ApplicationEventPublisher eventPublisher;

    public ParticipantDataService(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    // Store participants in a concurrent map for thread-safe updates
    private final Map<String, Participant> participantsMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void initialize() {
        loadStaticParticipants();
    }

    /**
     * Loads static participant data.
     * In the future, this could be replaced with database loading or external configuration.
     */
    private void loadStaticParticipants() {
        // Create static participants
        Participant p1 = new Participant();
        p1.setId("Participant_0yrbhi5");
        p1.setName("Student");
        p1.setPosition("place17");

        Participant p2 = new Participant();
        p2.setId("Participant_1fejmk0");
        p2.setName("Tutor");
        p2.setPosition("place1");

        // Add to map
        participantsMap.put(p1.getId(), p1);
        participantsMap.put(p2.getId(), p2);

        log.info("[ParticipantDataService] Initialized with {} static participants", participantsMap.size());
    }

    /**
     * Get all participants as a list.
     */
    public List<Participant> getParticipants() {
        return new ArrayList<>(participantsMap.values());
    }

    /**
     * Get a specific participant by ID.
     */
    public Optional<Participant> getParticipant(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(participantsMap.get(id));
    }

    /**
     * Get a participant by name (case-insensitive).
     */
    public Optional<Participant> getParticipantByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        return participantsMap.values().stream()
                .filter(participant -> participant.getName() != null
                        && participant.getName().equalsIgnoreCase(name.trim()))
                .findFirst();
    }

    /**
     * Update a participant's position.
     */
    public void updateParticipantPosition(String id, String position) {
        if (id == null) {
            return;
        }
        Participant participant = participantsMap.get(id);
        if (participant != null) {
            String oldPosition = participant.getPosition();
            participant.setPosition(position);
            log.debug("[ParticipantDataService] Updated participant {} position: {} -> {}", id, oldPosition, position);
            eventPublisher.publishEvent(new ParticipantPositionChangedEvent(id, oldPosition, position));
        } else {
            log.warn("[ParticipantDataService] Participant {} not found, cannot update position", id);
        }
    }

    /**
     * Add a new participant dynamically (if needed in the future).
     */
    public void addParticipant(Participant participant) {
        if (participant != null && participant.getId() != null) {
            participantsMap.put(participant.getId(), participant);
            log.info("[ParticipantDataService] Added new participant: {}", participant.getId());
        }
    }

    /**
     * Remove a participant dynamically (if needed in the future).
     */
    public void removeParticipant(String id) {
        if (id != null) {
            Participant removed = participantsMap.remove(id);
            if (removed != null) {
                log.info("[ParticipantDataService] Removed participant: {}", id);
            }
        }
    }

    /**
     * Check if any participants are loaded.
     */
    public boolean isLoaded() {
        return !participantsMap.isEmpty();
    }
}
