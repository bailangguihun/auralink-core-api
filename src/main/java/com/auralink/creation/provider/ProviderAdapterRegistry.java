package com.auralink.creation.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.capability.WorkflowCapabilityRegistry;

/**
 * Authoritative exact operation/provider adapter registry.
 *
 * <p>Construction fails if enabled ROUND 7 provider definitions drift from
 * adapter bindings or if the reserved video mapping gains an adapter.</p>
 */
@Component
public class ProviderAdapterRegistry {

    private final Map<Key, RegisteredAdapter> adapters;

    public ProviderAdapterRegistry(
            List<CreationProviderAdapter> implementations,
            WorkflowCapabilityRegistry workflowCapabilities) {
        boolean packagedMockActive = implementations.stream()
                .anyMatch(PackagedMockCreationProviderAdapter.class::isInstance);
        LinkedHashMap<Key, RegisteredAdapter> registered = new LinkedHashMap<>();
        for (CreationProviderAdapter adapter : implementations) {
            if (adapter == null || adapter.bindings() == null || adapter.bindings().isEmpty()) {
                throw new IllegalStateException("Creation provider adapter has no binding");
            }
            if (packagedMockActive
                    && !(adapter instanceof PackagedMockCreationProviderAdapter)
                    && adapter.bindings().stream().anyMatch(binding ->
                            binding.operation() != WorkflowOperation.PAINTING_TO_MUSIC)) {
                // The production adapters remain ordinary beans so normal startup
                // keeps its exact registry contract. The packaged harness replaces
                // only the four approved execution bindings; VMM remains present
                // solely to satisfy the frozen definition-time registry and is not
                // eligible for ROUND 9B.2 worker execution.
                continue;
            }
            for (ProviderAdapterBinding binding : adapter.bindings()) {
                Key key = new Key(binding.operation(), binding.providerCode());
                if (registered.putIfAbsent(key, new RegisteredAdapter(adapter, binding)) != null) {
                    throw new IllegalStateException("Duplicate creation provider adapter binding");
                }
            }
        }
        crossCheckWorkflowCapabilities(registered, workflowCapabilities);
        adapters = Map.copyOf(registered);
    }

    public Optional<CreationProviderAdapter> find(
            WorkflowOperation operation,
            String providerCode) {
        if (operation == null || providerCode == null || providerCode.isBlank()) {
            return Optional.empty();
        }
        RegisteredAdapter registered = adapters.get(new Key(operation, providerCode));
        return registered == null ? Optional.empty() : Optional.of(registered.adapter());
    }

    public CreationProviderAdapter require(
            WorkflowOperation operation,
            String providerCode) {
        return find(operation, providerCode).orElseThrow(() ->
                new ProviderExecutionException(
                        ProviderErrorCategory.PROVIDER_INTERNAL_CONTRACT_ERROR,
                        "No exact creation provider adapter is registered"));
    }

    public ProviderReadiness implementationReadiness(
            WorkflowOperation operation,
            String providerCode) {
        if (operation == WorkflowOperation.PAINTING_TO_VIDEO
                && "reserved-video".equals(providerCode)) {
            return ProviderReadiness.reservedDisabled();
        }
        return find(operation, providerCode)
                .map(ignored -> ProviderReadiness.implemented())
                .orElseGet(() -> new ProviderReadiness(
                        ProviderReadinessState.CONFIGURATION_INVALID,
                        "PROVIDER_ADAPTER_NOT_REGISTERED"));
    }

    public ProviderReadiness readiness(
            WorkflowOperation operation,
            String providerCode) {
        if (operation == WorkflowOperation.PAINTING_TO_VIDEO
                && "reserved-video".equals(providerCode)) {
            return ProviderReadiness.reservedDisabled();
        }
        return require(operation, providerCode).readiness();
    }

    public List<ProviderAdapterBinding> bindings() {
        return adapters.values().stream()
                .map(RegisteredAdapter::binding)
                .sorted(java.util.Comparator
                        .comparing((ProviderAdapterBinding binding) -> binding.operation().ordinal())
                        .thenComparing(ProviderAdapterBinding::providerCode))
                .toList();
    }

    private void crossCheckWorkflowCapabilities(
            Map<Key, RegisteredAdapter> registered,
            WorkflowCapabilityRegistry workflowCapabilities) {
        int expectedEnabledBindings = 0;
        for (var operation : workflowCapabilities.operations()) {
            for (var provider : operation.providers()) {
                Key key = new Key(operation.operation(), provider.code());
                RegisteredAdapter adapter = registered.get(key);
                if (operation.definitionEnabled() && provider.definitionEnabled()) {
                    expectedEnabledBindings++;
                    if (adapter == null
                            || adapter.binding().inputModality() != operation.inputModality()
                            || adapter.binding().outputModality() != operation.outputModality()) {
                        throw new IllegalStateException(
                                "Workflow capability and creation provider registry are inconsistent");
                    }
                } else if (adapter != null) {
                    throw new IllegalStateException(
                            "Disabled workflow provider must not have a creation adapter");
                }
            }
        }
        if (registered.size() != expectedEnabledBindings) {
            throw new IllegalStateException("Creation provider registry contains an unknown binding");
        }
    }

    private record Key(WorkflowOperation operation, String providerCode) {
    }

    private record RegisteredAdapter(
            CreationProviderAdapter adapter,
            ProviderAdapterBinding binding) {
    }
}
