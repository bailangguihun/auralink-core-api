package com.auralink.provider.vmm;

import com.auralink.creation.CreationSourceResolver;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.provider.validation.StrictProviderJson;
import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.service.WorkflowExecutionPreparer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Restricts controlled acceptance using the authenticated user and resolved server-side source. */
@Component
public class VmmManagedAdmission {
   private final VmmManagedGate gate;
   private final VmmEndpointResolver endpoint;
   private final ObjectMapper mapper;
   public VmmManagedAdmission(VmmManagedGate gate, VmmEndpointResolver endpoint, ObjectMapper mapper) {
      this.gate = gate;
      this.endpoint = endpoint;
      this.mapper = mapper;
   }
   public void require(long ownerId, WorkflowExecutionPreparer.PreparedWorkflow workflow, CreationSourceResolver.ResolvedSource source) {
      if (!gate.enabled()) return;
      for (var transform : workflow.transforms()) {
         if (transform.operation() != WorkflowOperation.PAINTING_TO_MUSIC) continue;
         int duration = AuralinkVmmProviderAdapter.DEFAULT_DURATION_SECONDS;
         try {
            var parameters = StrictProviderJson.parse(mapper, transform.parametersJson());
            if (parameters != null && parameters.hasNonNull("durationSeconds")) {
               var value = parameters.get("durationSeconds");
               if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 3 || value.intValue() > 30) throw new IllegalArgumentException();
               duration = value.intValue();
            }
         } catch (Exception exception) {
            throw new ProviderExecutionException(ProviderErrorCategory.PROVIDER_REJECTED, "Managed VMM duration is invalid");
         }
         gate.requireSubmission(endpoint.resolveGenerationEndpoint(), ownerId,
            source.sourcePainting() == null ? null : source.sourcePainting().getId(), duration);
      }
   }
}
