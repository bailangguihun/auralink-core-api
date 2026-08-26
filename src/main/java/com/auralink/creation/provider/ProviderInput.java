package com.auralink.creation.provider;

import com.auralink.workflow.WorkflowModality;

/** Resolved and authorized provider input supplied by future ROUND 9 code. */
public sealed interface ProviderInput permits ProviderTextInput, ProviderImageInput {
    WorkflowModality modality();
}
