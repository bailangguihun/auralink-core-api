package com.auralink.api.v1.creation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.auralink.creation.CreationSubmissionService;
import com.auralink.creation.CreationRetryService;

import lombok.RequiredArgsConstructor;

/** Authenticated Creation admission and owner-scoped polling endpoints. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CreationController {

    private final CreationSubmissionService creationService;
    private final CreationRetryService retryService;

    @PostMapping("/creations")
    public ResponseEntity<CreationQueuedResponse> submit(@RequestBody CreationSubmissionRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(creationService.submit(request));
    }

    @GetMapping("/creations/{creationId}")
    public CreationDetailResponse get(@PathVariable String creationId) {
        return creationService.get(creationId);
    }

    @PostMapping("/creations/{creationId}/retry")
    public ResponseEntity<CreationRetryResponse> retry(
            @PathVariable String creationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreationRetryRequest request) {
        CreationRetryService.RetryAdmission admission = retryService.retry(creationId, idempotencyKey, request);
        return ResponseEntity.status(admission.idempotentReplay() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(admission.response());
    }

    @GetMapping("/me/creations")
    public CreationPageResponse list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return creationService.list(page, size);
    }
}
