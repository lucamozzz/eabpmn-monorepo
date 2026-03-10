package org.unicam.intermediate.listener.execution;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.springframework.stereotype.Component;
import org.unicam.intermediate.models.Participant;
import org.unicam.intermediate.models.enums.TaskType;
import org.unicam.intermediate.service.environmental.EnvironmentDataService;
import org.unicam.intermediate.service.environmental.movement.MovementTaskRegistry;
import org.unicam.intermediate.service.participant.ParticipantDataService;
import org.unicam.intermediate.service.participant.ParticipantService;
import org.unicam.intermediate.service.participant.UserParticipantMappingService;
import org.unicam.intermediate.service.xml.AbstractXmlService;
import org.unicam.intermediate.service.xml.XmlServiceDispatcher;

import java.util.List;

import static org.unicam.intermediate.utils.Constants.*;

@Slf4j
@Component(movementExecutionListenerBeanName)
public class MovementExecutionListener implements ExecutionListener {

    private final XmlServiceDispatcher dispatcher;
    private final ParticipantService participantService;
    private final ParticipantDataService participantDataService;
    private final EnvironmentDataService environmentDataService;
    private final UserParticipantMappingService userParticipantMapping;
    private final MovementTaskRegistry movementTaskRegistry;

    public MovementExecutionListener(XmlServiceDispatcher dispatcher,
                                     ParticipantService participantService,
                                     ParticipantDataService participantDataService,
                                     EnvironmentDataService environmentDataService,
                                     UserParticipantMappingService userParticipantMapping,
                                     MovementTaskRegistry movementTaskRegistry) {
        this.dispatcher = dispatcher;
        this.participantService = participantService;
        this.participantDataService = participantDataService;
        this.environmentDataService = environmentDataService;
        this.userParticipantMapping = userParticipantMapping;
        this.movementTaskRegistry = movementTaskRegistry;
    }

    @Override
    public void notify(DelegateExecution execution) {
        if (EVENTNAME_START.equals(execution.getEventName())) {
            handleMovementStart(execution);
        } else if (EVENTNAME_END.equals(execution.getEventName())) {
            handleMovementEnd(execution);
        }
    }

    private void handleMovementStart(DelegateExecution execution) {
        AbstractXmlService svc = dispatcher.get(SPACE_NS.getNamespaceUri(), TaskType.MOVEMENT);
        String raw = svc.extractRaw(execution);
        String value = raw != null && raw.startsWith("${") && raw.endsWith("}")
                ? String.valueOf(execution.getVariable(raw.substring(2, raw.length()-1).trim()))
                : raw;

        Participant participant = participantService.resolveCurrentParticipant(execution);
        boolean destinationIsPhysicalPlace = value != null && environmentDataService.getPhysicalPlace(value).isPresent();
        boolean destinationIsLogicalPlace = value != null && environmentDataService.getLogicalPlaces().stream()
            .anyMatch(lp -> value.equals(lp.getId()));

        if (participant != null && destinationIsLogicalPlace) {
            List<String> matchingPhysicalPlaces = movementTaskRegistry
                .resolveMatchingPhysicalPlaceIdsForLogicalDestination(value);

            if (matchingPhysicalPlaces.isEmpty()) {
            throw new BpmnError(
                "unreachableDestination",
                String.format("Logical destination '%s' has no matching physical places for participant '%s'",
                    value, participant.getId())
            );
            }
        }

        if (participant != null && destinationIsPhysicalPlace) {
            String participantId = participant.getId();
            participantDataService.getParticipant(participantId).ifPresent(current -> {
                String currentPosition = current.getPosition();
                boolean reachable = environmentDataService.existsPathBetweenPhysicalPlaces(currentPosition, value);
                if (!reachable) {
                    throw new BpmnError(
                            "unreachableDestination",
                            String.format("No path found from '%s' to '%s' for participant '%s'",
                                    currentPosition, value, participantId)
                    );
                }
            });
        }

        svc.patchInstanceValue(execution, value);
        var activityId = execution.getCurrentActivityId();
        String varKey = activityId + "." + svc.getLocalName();
        execution.setVariable(varKey, value);

        String businessKey = execution.getBusinessKey();

        String userId = (String) execution.getVariable("userId");

        if (userId != null && participant != null && businessKey != null) {
            userParticipantMapping.registerUserAsParticipant(
                    businessKey,
                    userId,
                    participant.getId()
            );

            log.info("[MOVEMENT] Auto-registered user {} as participant {} for BK {}",
                    userId, participant.getId(), businessKey);
        }

        String activityName = execution.getCurrentActivityName();
        
        log.info("[MOVEMENT] WAITING | Activity: {} - {} | Participant: {} | Destination: {}", 
                execution.getCurrentActivityId(),
                activityName != null ? activityName : "(unnamed)",
                participant.toString(),
                value != null ? value : "unknown location");

        // Registra il task nel registry per il controllo periodico
        if (participant != null && value != null) {
            movementTaskRegistry.registerTask(participant.getId(), execution.getId(), value);
        }
    }

    private void handleMovementEnd(DelegateExecution execution) {
        AbstractXmlService svc = dispatcher.get(SPACE_NS.getNamespaceUri(), TaskType.MOVEMENT);
        String raw = svc.extractRaw(execution);
        svc.restoreInstanceValue(execution, raw);

        // Rimuovi il task dal registry quando termina
        Participant participant = participantService.resolveCurrentParticipant(execution);
        if (participant != null) {
            movementTaskRegistry.removeTask(participant.getId());
            log.info("[MOVEMENT] Task completed for participant {}", participant.getId());
        }
    }
}