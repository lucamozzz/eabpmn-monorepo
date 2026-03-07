package org.unicam.intermediate.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.bpmn.instance.MessageFlow;
import org.camunda.bpm.model.xml.instance.DomElement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class MessageFlowRegistry {

    @Autowired
    @Lazy  // Break circular dependency by lazy loading
    private RepositoryService repositoryService;

    // Key: processDefinitionId:taskId -> MessageFlowBinding
    private final Map<String, MessageFlowBinding> flowBindings = new ConcurrentHashMap<>();

    @Data
    public static class MessageFlowBinding {
        private String flowId;
        private String type; // binding/unbinding
        private String sourceParticipantRef;
        private String targetParticipantRef;
        private String sourceTaskRef;
        private String targetTaskRef;
    }

    /**
     * Register message flows for a process. Can be called during parsing or after deployment.
     */
    public void registerFlowsForProcess(String processDefinitionId, BpmnModelInstance model) {
        if (model == null) {
            log.warn("[MessageFlowRegistry] Model is null for process: {}", processDefinitionId);
            return;
        }

        model.getModelElementsByType(MessageFlow.class).forEach(flow -> {
            ExtensionElements ext = flow.getExtensionElements();
            if (ext == null) return;

            String flowType = extractExtensionValue(ext, "type");
            if (!"binding".equals(flowType) && !"unbinding".equals(flowType)) return;

            MessageFlowBinding binding = new MessageFlowBinding();
            binding.setFlowId(flow.getId());
            binding.setType(flowType);
            binding.setSourceParticipantRef(extractExtensionValue(ext, "sourceRef"));
            binding.setTargetParticipantRef(extractExtensionValue(ext, "targetRef"));

            // Get task references from the flow source and target
            if (flow.getSource() != null) {
                binding.setSourceTaskRef(flow.getSource().getId());
            }
            if (flow.getTarget() != null) {
                binding.setTargetTaskRef(flow.getTarget().getId());
            }

            // Register for both source and target tasks
            if (binding.getSourceTaskRef() != null) {
                String sourceKey = processDefinitionId + ":" + binding.getSourceTaskRef();
                flowBindings.put(sourceKey, binding);
            }

            if (binding.getTargetTaskRef() != null) {
                String targetKey = processDefinitionId + ":" + binding.getTargetTaskRef();
                flowBindings.put(targetKey, binding);
            }

            log.info("[MessageFlowRegistry] Registered {} flow: {} -> {} (participants: {} -> {})",
                    flowType, binding.getSourceTaskRef(), binding.getTargetTaskRef(),
                    binding.getSourceParticipantRef(), binding.getTargetParticipantRef());
        });
    }

    /**
     * Try to register flows after deployment if not done during parsing
     */
    public void ensureFlowsRegistered(String processDefinitionId) {
        String testKey = processDefinitionId + ":test";
        if (!flowBindings.keySet().stream().anyMatch(k -> k.startsWith(processDefinitionId + ":"))) {
            try {
                if (repositoryService != null) {
                    BpmnModelInstance model = repositoryService.getBpmnModelInstance(processDefinitionId);
                    registerFlowsForProcess(processDefinitionId, model);
                    log.info("[MessageFlowRegistry] Late registration of flows for process: {}", processDefinitionId);
                }
            } catch (Exception e) {
                log.error("[MessageFlowRegistry] Failed to register flows for process {}: {}",
                        processDefinitionId, e.getMessage());
            }
        }
    }

    public MessageFlowBinding getFlowBinding(String processDefinitionId, String taskId) {
        // Ensure flows are registered (in case parse listener couldn't access repository)
        ensureFlowsRegistered(processDefinitionId);

        return flowBindings.get(processDefinitionId + ":" + taskId);
    }

    private String extractExtensionValue(ExtensionElements ext, String localName) {
        return ext.getDomElement().getChildElements().stream()
                .filter(dom -> localName.equals(dom.getLocalName())
                        && "http://space".equals(dom.getNamespaceURI()))
                .map(DomElement::getTextContent)
                .map(String::trim)
                .findFirst()
                .orElse(null);
    }

    /**
     * Clear registry (useful for testing or redeployment)
     */
    public void clear() {
        flowBindings.clear();
        log.info("[MessageFlowRegistry] Cleared all flow bindings");
    }
}