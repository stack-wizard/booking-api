package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.LoyaltyDtos;
import com.stackwizard.booking_api.model.LoyaltyImportBatch;
import com.stackwizard.booking_api.security.LoyaltyAccess;
import com.stackwizard.booking_api.security.LoyaltyPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.loyalty.imports.LoyaltyImportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CSV imports: POST creates the batch and returns a presigned S3 PUT URL (large files go straight to S3) and an
 * API upload path (multipart, for small files or when the client cannot reach S3); the client uploads the file,
 * then calls /start. Progress is polled on GET; a VALIDATED batch is applied with /apply.
 */
@RestController
@RequestMapping("/api/loyalty/imports")
public class LoyaltyImportController {
    private final LoyaltyImportService importService;
    private final LoyaltyAccess access;

    public LoyaltyImportController(LoyaltyImportService importService, LoyaltyAccess access) {
        this.importService = importService;
        this.access = access;
    }

    @GetMapping("/fields")
    public List<LoyaltyImportService.FieldInfo> fields(@RequestParam LoyaltyImportBatch.ImportType type) {
        access.require(LoyaltyPermission.IMPORT);
        return importService.fields(type);
    }

    @GetMapping
    public List<LoyaltyImportBatch> list() {
        access.require(LoyaltyPermission.IMPORT);
        return importService.list(TenantResolver.requireOrgTenantId());
    }

    @PostMapping
    public LoyaltyImportService.Upload create(@RequestBody LoyaltyDtos.ImportCreateRequest body) {
        access.require(LoyaltyPermission.IMPORT);
        return importService.create(TenantResolver.requireOrgTenantId(), body.programId(), body.importType(),
                body.propertyTenantId(), body.fileName(), body.fileSize(), access.currentUserId());
    }

    @PostMapping(value = "/{batchId}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LoyaltyImportBatch uploadFile(@PathVariable Long batchId, @RequestParam("file") MultipartFile file) throws java.io.IOException {
        access.require(LoyaltyPermission.IMPORT);
        return importService.uploadFile(TenantResolver.requireOrgTenantId(), batchId, file.getBytes(), file.getContentType());
    }

    @GetMapping("/{batchId}")
    public LoyaltyImportBatch get(@PathVariable Long batchId) {
        access.require(LoyaltyPermission.IMPORT);
        return importService.require(TenantResolver.requireOrgTenantId(), batchId);
    }

    @PostMapping("/{batchId}/start")
    public LoyaltyImportBatch start(@PathVariable Long batchId, @RequestBody(required = false) LoyaltyDtos.ImportStartRequest body) {
        access.require(LoyaltyPermission.IMPORT);
        return importService.start(TenantResolver.requireOrgTenantId(), batchId,
                body == null ? null : body.columnMapping(), body == null ? null : body.options());
    }

    @PostMapping("/{batchId}/apply")
    public LoyaltyImportBatch apply(@PathVariable Long batchId) {
        access.require(LoyaltyPermission.IMPORT);
        return importService.apply(TenantResolver.requireOrgTenantId(), batchId);
    }

    @PostMapping("/{batchId}/cancel")
    public LoyaltyImportBatch cancel(@PathVariable Long batchId) {
        access.require(LoyaltyPermission.IMPORT);
        return importService.cancel(TenantResolver.requireOrgTenantId(), batchId);
    }

    @GetMapping("/{batchId}/errors.csv")
    public ResponseEntity<byte[]> errors(@PathVariable Long batchId) {
        access.require(LoyaltyPermission.IMPORT);
        byte[] body = importService.errorReport(TenantResolver.requireOrgTenantId(), batchId).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"import-" + batchId + "-errors.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }
}
