package com.auralink.creation.provider;

import java.util.List;

/** Internal adapter contract; it performs one bounded transform and no persistence. */
public interface CreationProviderAdapter {

    List<ProviderAdapterBinding> bindings();

    ProviderReadiness readiness();

    ProviderExecutionResult execute(ProviderExecutionRequest request);
}
