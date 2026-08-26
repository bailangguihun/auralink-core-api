package com.auralink.api.v1.workflow;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.auralink.config.properties.WorkflowProperties;
import com.auralink.creation.CreationRuntimeCapabilityService;
import com.auralink.workflow.capability.WorkflowCapabilityRegistry;

import lombok.RequiredArgsConstructor;

/** Authenticated static capability discovery; it never contacts providers. */
@RestController
@RequestMapping("/api/v1/workflow")
@RequiredArgsConstructor
public class WorkflowCapabilityController {

    private final WorkflowProperties properties;
    private final WorkflowCapabilityRegistry registry;
    private final CreationRuntimeCapabilityService creationCapabilities;

    @GetMapping("/node-types")
    public WorkflowNodeTypesResponse nodeTypes() {
        return WorkflowNodeTypesResponse.from(
                properties.getSchemaVersion(), properties.isEnabled(), registry, creationCapabilities);
    }
}
