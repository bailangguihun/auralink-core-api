package com.auralink.creation.provider;

/**
 * Marker contract for immutable provider diagnostics that contain only bounded,
 * operator-safe structural metadata.
 *
 * @param <S> stable validation-stage enum
 * @param <C> stable validation-code enum
 * @param <R> typed safe response-shape metadata
 */
public interface ProviderSafeDiagnostic<S extends Enum<S>, C extends Enum<C>, R> {

    S validationStage();

    C validationCode();

    R responseShape();
}
