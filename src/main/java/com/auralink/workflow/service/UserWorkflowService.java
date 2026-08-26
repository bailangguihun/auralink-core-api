package com.auralink.workflow.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.api.v1.workflow.WorkflowDefinitionRequest;
import com.auralink.api.v1.workflow.WorkflowDetailResponse;
import com.auralink.api.v1.workflow.WorkflowPageResponse;
import com.auralink.api.v1.workflow.WorkflowSummaryResponse;
import com.auralink.api.v1.workflow.WorkflowValidationResponse;
import com.auralink.config.properties.WorkflowProperties;
import com.auralink.entity.User;
import com.auralink.entity.UserWorkflow;
import com.auralink.repository.UserWorkflowRepository;
import com.auralink.service.CurrentUserService;
import com.auralink.workflow.graph.WorkflowValidationResult;
import com.auralink.workflow.graph.WorkflowValidator;
import com.auralink.workflow.snapshot.WorkflowSnapshotFactory;
import com.auralink.workflow.snapshot.WorkflowSnapshotResult;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** Private owner-scoped workflow definition lifecycle; it performs no execution. */
@Service
@RequiredArgsConstructor
public class UserWorkflowService {

    public static final String ACTIVE_STATUS = "ACTIVE";
    private static final int MAX_PAGE_SIZE = 100;

    private final WorkflowFeatureGuard featureGuard;
    private final WorkflowProperties properties;
    private final CurrentUserService currentUserService;
    private final UserWorkflowRepository workflows;
    private final WorkflowValidator validator;
    private final WorkflowResponseMapper responseMapper;
    private final WorkflowSnapshotFactory snapshotFactory;
    private final EntityManager entityManager;

    @Transactional
    public WorkflowDetailResponse create(WorkflowDefinitionRequest request) {
        featureGuard.requireEnabled();
        User owner = currentUserService.requireCurrentUser();
        WorkflowValidationResult validation = requireValid(request);
        UserWorkflow entity = UserWorkflow.builder()
                .user(owner)
                .name(validation.normalizedName())
                .description(validation.normalizedDescription())
                .graphJson(validation.canonicalization().canonicalJson())
                .schemaVersion(properties.getSchemaVersion())
                .status(ACTIVE_STATUS)
                .build();
        workflows.saveAndFlush(entity);
        refreshPersisted(entity);
        return responseMapper.detail(entity);
    }

    @Transactional(readOnly = true)
    public WorkflowPageResponse list(int page, int size) {
        featureGuard.requireEnabled();
        validatePage(page, size);
        User owner = currentUserService.requireCurrentUser();
        Sort sort = Sort.by(Sort.Direction.DESC, "updatedAt")
                .and(Sort.by(Sort.Direction.ASC, "publicId"));
        Page<UserWorkflow> result = workflows.findAllByUser_IdAndStatus(
                owner.getId(), ACTIVE_STATUS, PageRequest.of(page, size, sort));
        List<WorkflowSummaryResponse> items = result.getContent().stream()
                .map(responseMapper::summary)
                .toList();
        return WorkflowPageResponse.from(result, items);
    }

    @Transactional(readOnly = true)
    public WorkflowDetailResponse get(String workflowId) {
        featureGuard.requireEnabled();
        User owner = currentUserService.requireCurrentUser();
        return responseMapper.detail(findOwned(workflowId, owner));
    }

    @Transactional
    public WorkflowDetailResponse replace(
            String workflowId,
            WorkflowDefinitionRequest request) {
        featureGuard.requireEnabled();
        User owner = currentUserService.requireCurrentUser();
        UserWorkflow entity = findOwned(workflowId, owner);
        WorkflowValidationResult validation = requireValid(request);
        entity.setName(validation.normalizedName());
        entity.setDescription(validation.normalizedDescription());
        entity.setGraphJson(validation.canonicalization().canonicalJson());
        entity.setSchemaVersion(properties.getSchemaVersion());
        entity.setStatus(ACTIVE_STATUS);
        workflows.saveAndFlush(entity);
        refreshPersisted(entity);
        return responseMapper.detail(entity);
    }

    @Transactional
    public void delete(String workflowId) {
        featureGuard.requireEnabled();
        User owner = currentUserService.requireCurrentUser();
        UserWorkflow entity = findOwned(workflowId, owner);
        workflows.delete(entity);
        workflows.flush();
    }

    @Transactional(readOnly = true)
    public WorkflowValidationResponse validate(WorkflowDefinitionRequest request) {
        featureGuard.requireEnabled();
        currentUserService.requireCurrentUser();
        return WorkflowValidationResponse.from(
                validator.validate(request), properties.getSchemaVersion());
    }

    /** Detached snapshot factory entry point reserved for future ROUND 9 execution. */
    @Transactional(readOnly = true)
    public WorkflowSnapshotResult snapshotOwned(String workflowId) {
        featureGuard.requireEnabled();
        User owner = currentUserService.requireCurrentUser();
        UserWorkflow entity = findOwned(workflowId, owner);
        StoredWorkflowDefinition stored = responseMapper.parse(entity);
        return snapshotFactory.create(
                entity.getPublicId(),
                entity.getName(),
                entity.getSchemaVersion(),
                stored.graph());
    }

    private WorkflowValidationResult requireValid(WorkflowDefinitionRequest request) {
        WorkflowValidationResult validation = validator.validate(request);
        if (validation.valid()) {
            return validation;
        }
        ApiErrorCode errorCode = ApiErrorCode.WORKFLOW_INVALID;
        if (hasViolation(validation, ApiErrorCode.WORKFLOW_SCHEMA_UNSUPPORTED.name())) {
            errorCode = ApiErrorCode.WORKFLOW_SCHEMA_UNSUPPORTED;
        } else if (hasViolation(validation, ApiErrorCode.WORKFLOW_GRAPH_TOO_LARGE.name())) {
            errorCode = ApiErrorCode.WORKFLOW_GRAPH_TOO_LARGE;
        }
        throw new ApiV1Exception(
                HttpStatus.UNPROCESSABLE_ENTITY,
                errorCode,
                "工作流定义验证失败",
                validation.violations());
    }

    private boolean hasViolation(WorkflowValidationResult validation, String code) {
        return validation.violations().stream().anyMatch(violation -> code.equals(violation.code()));
    }

    private UserWorkflow findOwned(String workflowId, User owner) {
        String canonicalId = canonicalWorkflowId(workflowId);
        return workflows.findByPublicIdAndUser_IdAndStatus(
                        canonicalId, owner.getId(), ACTIVE_STATUS)
                .orElseThrow(UserWorkflowService::notFound);
    }

    private String canonicalWorkflowId(String workflowId) {
        try {
            String canonical = UUID.fromString(workflowId).toString();
            if (!canonical.equals(workflowId)) {
                throw notFound();
            }
            return canonical;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw notFound();
        }
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw new ApiV1Exception(
                    HttpStatus.BAD_REQUEST,
                    ApiErrorCode.BAD_REQUEST,
                    "页码必须大于或等于 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiV1Exception(
                    HttpStatus.BAD_REQUEST,
                    ApiErrorCode.INVALID_PAGE_SIZE,
                    "每页数量必须在 1 到 100 之间");
        }
    }

    private void refreshPersisted(UserWorkflow entity) {
        entityManager.flush();
        entityManager.refresh(entity);
    }

    private static ApiV1Exception notFound() {
        return new ApiV1Exception(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.WORKFLOW_NOT_FOUND,
                "工作流不存在");
    }
}
