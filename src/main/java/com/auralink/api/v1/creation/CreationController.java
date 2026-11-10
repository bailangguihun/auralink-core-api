package com.auralink.api.v1.creation;

import com.auralink.creation.CreationRetryService;
import com.auralink.creation.CreationSubmissionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CreationController {
   private final CreationSubmissionService creationService;
   private final CreationRetryService retryService;

   @PostMapping("/creations")
   public ResponseEntity<CreationQueuedResponse> submit(@RequestBody CreationSubmissionRequest request) {
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(this.creationService.submit(request));
   }

   @GetMapping("/creations/{creationId}")
   public CreationDetailResponse get(@PathVariable String creationId) {
      return this.creationService.get(creationId);
   }

   @PostMapping("/creations/{creationId}/retry")
   public ResponseEntity<CreationRetryResponse> retry(
      @PathVariable String creationId,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody CreationRetryRequest request
   ) {
      CreationRetryService.RetryAdmission admission = this.retryService.retry(creationId, idempotencyKey, request);
      return ResponseEntity.status(admission.idempotentReplay() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(admission.response());
   }

   @DeleteMapping("/me/creations/{creationId}")
   public ResponseEntity<Void> deleteHistory(@PathVariable String creationId) {
      this.creationService.deleteHistory(creationId);
      return ResponseEntity.noContent().build();
   }

   @GetMapping("/me/creations")
   public CreationPageResponse list(@RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Integer size) {
      return this.creationService.list(page, size);
   }

   public CreationController(final CreationSubmissionService creationService, final CreationRetryService retryService) {
      this.creationService = creationService;
      this.retryService = retryService;
   }
}
