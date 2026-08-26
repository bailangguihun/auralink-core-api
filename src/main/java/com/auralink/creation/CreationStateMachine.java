package com.auralink.creation;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Internal transition guard for persisted Creation state only.  It contains no
 * execution, provider, repository, or controller behavior.
 */
@Component
public class CreationStateMachine {

    private static final Map<CreationStatus, Set<CreationStatus>> CREATION_TRANSITIONS =
            creationTransitions();
    private static final Map<CreationStepStatus, Set<CreationStepStatus>> STEP_TRANSITIONS =
            stepTransitions();

    void requireInitial(CreationStatus target) {
        if (target != CreationStatus.QUEUED) {
            throw new IllegalStateException("Creation submission must begin in QUEUED");
        }
    }

    void requireCreationTransition(CreationStatus from, CreationStatus to) {
        requireTransition(CREATION_TRANSITIONS, from, to, "Creation");
    }

    /**
     * Reserved retry gateway.  A later retry service must explicitly use this
     * method; the regular worker transition table cannot reopen terminal work.
     */
    void requireRetryTransition(CreationStatus from, CreationStatus to) {
        if (to != CreationStatus.QUEUED
                || (from != CreationStatus.FAILED && from != CreationStatus.PARTIAL_SUCCESS)) {
            throw new IllegalStateException("Creation retry state transition is not allowed");
        }
    }

    void requireStepTransition(CreationStepStatus from, CreationStepStatus to) {
        requireTransition(STEP_TRANSITIONS, from, to, "CreationStep");
    }

    private static <T extends Enum<T>> void requireTransition(
            Map<T, Set<T>> transitions,
            T from,
            T to,
            String resource) {
        if (from == null || to == null || !transitions.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateException(resource + " state transition is not allowed");
        }
    }

    private static Map<CreationStatus, Set<CreationStatus>> creationTransitions() {
        EnumMap<CreationStatus, Set<CreationStatus>> transitions = new EnumMap<>(CreationStatus.class);
        transitions.put(CreationStatus.QUEUED, EnumSet.of(CreationStatus.RUNNING));
        transitions.put(CreationStatus.RUNNING, EnumSet.of(
                CreationStatus.SUCCEEDED,
                CreationStatus.PARTIAL_SUCCESS,
                CreationStatus.FAILED));
        // FAILED and PARTIAL_SUCCESS may return to QUEUED only through the later retry service.
        // ROUND 9B.1 keeps that privileged retry entry point unimplemented.
        transitions.put(CreationStatus.SUCCEEDED, Set.of());
        transitions.put(CreationStatus.PARTIAL_SUCCESS, Set.of());
        transitions.put(CreationStatus.FAILED, Set.of());
        return Map.copyOf(transitions);
    }

    private static Map<CreationStepStatus, Set<CreationStepStatus>> stepTransitions() {
        EnumMap<CreationStepStatus, Set<CreationStepStatus>> transitions =
                new EnumMap<>(CreationStepStatus.class);
        transitions.put(CreationStepStatus.PENDING, EnumSet.of(
                CreationStepStatus.RUNNING,
                CreationStepStatus.SKIPPED));
        transitions.put(CreationStepStatus.RUNNING, EnumSet.of(
                CreationStepStatus.SUCCEEDED,
                CreationStepStatus.FAILED));
        transitions.put(CreationStepStatus.SUCCEEDED, Set.of());
        transitions.put(CreationStepStatus.FAILED, Set.of());
        transitions.put(CreationStepStatus.SKIPPED, Set.of());
        return Map.copyOf(transitions);
    }
}
