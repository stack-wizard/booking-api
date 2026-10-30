package com.stackwizard.booking_api.service.loyalty.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwizard.booking_api.model.LoyaltyImportBatch;
import com.stackwizard.booking_api.model.LoyaltyImportFile;
import com.stackwizard.booking_api.model.LoyaltyImportRow;
import com.stackwizard.booking_api.repository.LoyaltyImportBatchRepository;
import com.stackwizard.booking_api.repository.LoyaltyImportFileRepository;
import com.stackwizard.booking_api.repository.LoyaltyImportRowRepository;
import com.stackwizard.booking_api.repository.PlatformTenantMappingRepository;
import com.stackwizard.booking_api.service.loyalty.LoyaltyProgramService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Import lifecycle: create (presigned upload URL) → start (QUEUED, worker stages + validates) → VALIDATED (report)
 * → apply (APPLY_QUEUED, worker applies) → DONE. Validation never writes business data; only apply does.
 */
@Service
public class LoyaltyImportService {
    static final Set<LoyaltyImportBatch.Status> CANCELLABLE = Set.of(LoyaltyImportBatch.Status.UPLOADED,
            LoyaltyImportBatch.Status.QUEUED, LoyaltyImportBatch.Status.STAGING, LoyaltyImportBatch.Status.VALIDATED);

    private final LoyaltyImportBatchRepository batchRepo;
    private final LoyaltyImportRowRepository rowRepo;
    private final PlatformTenantMappingRepository mappingRepo;
    private final LoyaltyProgramService programService;
    private final ImportStorage storage;
    private final LoyaltyImportFileRepository fileRepo;
    private final Storage storageMode;
    private final Map<LoyaltyImportBatch.ImportType, ImportApplier> appliers = new EnumMap<>(LoyaltyImportBatch.ImportType.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LoyaltyImportService(LoyaltyImportBatchRepository batchRepo,
                                LoyaltyImportRowRepository rowRepo,
                                PlatformTenantMappingRepository mappingRepo,
                                LoyaltyProgramService programService,
                                ImportStorage storage,
                                List<ImportApplier> applierBeans,
                                LoyaltyImportFileRepository fileRepo,
                                @Value("${loyalty.import.storage:S3}") Storage storageMode) {
        this.batchRepo = batchRepo;
        this.rowRepo = rowRepo;
        this.mappingRepo = mappingRepo;
        this.programService = programService;
        this.storage = storage;
        applierBeans.forEach(a -> appliers.put(a.type(), a));
        this.fileRepo = fileRepo;
        this.storageMode = storageMode;
    }

    /** Where the uploaded CSV waits for the worker: S3 (default; presigned PUT or server-side put) or the database. */
    public enum Storage { S3, DB }

    /**
     * How the client gets the file in: {@code uploadUrl} is a presigned S3 PUT (null when the file must go through
     * the API), {@code uploadPath} is the API endpoint that accepts a multipart upload (always available).
     */
    public record Upload(LoyaltyImportBatch batch, String uploadUrl, String method, long expiresInSeconds, String uploadPath) {
    }

    public record FieldInfo(String field, List<String> aliases) {
    }

    public List<FieldInfo> fields(LoyaltyImportBatch.ImportType type) {
        return applier(type).fields().entrySet().stream().map(e -> new FieldInfo(e.getKey(), e.getValue())).toList();
    }

    public List<LoyaltyImportBatch> list(Long tenantId) {
        return batchRepo.findTop50ByTenantIdOrderByIdDesc(tenantId);
    }

    public LoyaltyImportBatch require(Long tenantId, Long batchId) {
        return batchRepo.findByIdAndTenantId(batchId, tenantId).orElseThrow(() -> new IllegalArgumentException("Import not found"));
    }

    @Transactional
    public Upload create(Long tenantId, Long programId, LoyaltyImportBatch.ImportType type, Long propertyTenantId,
                         String fileName, Long fileSize, Long userId) {
        if (type == null) {
            throw new IllegalArgumentException("importType is required");
        }
        if (type == LoyaltyImportBatch.ImportType.MEMBERS) {
            programService.require(tenantId, programId);
        } else if (programId != null) {
            programService.require(tenantId, programId);
        }
        if (type == LoyaltyImportBatch.ImportType.STAYS) {
            if (propertyTenantId == null) {
                throw new IllegalArgumentException("propertyTenantId (hotel) is required for a reservation import");
            }
            boolean ownHotel = mappingRepo.findByTenantId(propertyTenantId)
                    .map(m -> Objects.equals(m.getParentTenantId(), tenantId)).orElse(false);
            if (!ownHotel) {
                throw new IllegalArgumentException("Hotel does not belong to this organization");
            }
        }
        LoyaltyImportBatch batch = batchRepo.save(LoyaltyImportBatch.builder()
                .tenantId(tenantId)
                .programId(programId)
                .importType(type)
                .propertyTenantId(type == LoyaltyImportBatch.ImportType.STAYS ? propertyTenantId : null)
                .s3Key("pending")
                .fileName(StringUtils.hasText(fileName) ? fileName.trim() : "import.csv")
                .fileSize(fileSize)
                .columnMapping(objectMapper.createObjectNode())
                .options(objectMapper.createObjectNode())
                .status(LoyaltyImportBatch.Status.UPLOADED)
                .rowsTotal(0).rowsValid(0).rowsInvalid(0).rowsApplied(0).lastAppliedRow(0)
                .createdBy(userId)
                .updatedAt(OffsetDateTime.now())
                .build());
        String uploadPath = "/api/loyalty/imports/" + batch.getId() + "/file";
        if (storageMode == Storage.DB) {
            batch.setS3Key("db://" + tenantId + "/" + batch.getId());
            batchRepo.save(batch);
            return new Upload(batch, null, "POST", 0, uploadPath);
        }
        batch.setS3Key(storage.keyFor(tenantId, batch.getId()));
        batchRepo.save(batch);
        return new Upload(batch, storage.presignPut(batch.getS3Key()), "PUT", ImportStorage.UPLOAD_URL_TTL.toSeconds(), uploadPath);
    }

    /** Multipart upload through the API: to S3 server-side, or into the database when that is the configured storage. */
    @Transactional
    public LoyaltyImportBatch uploadFile(Long tenantId, Long batchId, byte[] content, String contentType) {
        LoyaltyImportBatch batch = require(tenantId, batchId);
        if (batch.getStatus() != LoyaltyImportBatch.Status.UPLOADED && batch.getStatus() != LoyaltyImportBatch.Status.FAILED) {
            throw new IllegalStateException("Import is " + batch.getStatus() + "; the file can only be uploaded before it starts");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("File is empty");
        }
        if (storageMode == Storage.DB) {
            fileRepo.save(LoyaltyImportFile.builder().batchId(batchId).content(content).uploadedAt(OffsetDateTime.now()).build());
        } else {
            storage.put(batch.getS3Key(), content, contentType);
        }
        batch.setFileSize((long) content.length);
        batch.setUpdatedAt(OffsetDateTime.now());
        return batchRepo.save(batch);
    }

    /** Size of the uploaded file, empty when nothing has been uploaded yet. */
    public java.util.Optional<Long> fileSize(LoyaltyImportBatch batch) {
        if (storageMode == Storage.DB) {
            return fileRepo.contentLength(batch.getId()).map(Integer::longValue);
        }
        return storage.size(batch.getS3Key());
    }

    /** The uploaded file as a stream, wherever it is kept. */
    public InputStream open(LoyaltyImportBatch batch) throws IOException {
        if (storageMode == Storage.DB) {
            byte[] content = fileRepo.findById(batch.getId()).map(LoyaltyImportFile::getContent)
                    .orElseThrow(() -> new IllegalStateException("File not uploaded yet"));
            return new ByteArrayInputStream(content);
        }
        return storage.open(batch.getS3Key());
    }

    /** Drop the database copy of the file once the batch is finished; S3 objects follow the bucket lifecycle. */
    @Transactional
    public void discardFile(Long batchId) {
        if (fileRepo.existsById(batchId)) {
            fileRepo.deleteById(batchId);
        }
    }

    /**
     * Options: {@code delimiter}, {@code dateFormat} (e.g. dd.MM.yyyy), {@code asOf} (opening balance date),
     * {@code suppressEvents} (default true for members: no welcome mails for migrated members),
     * {@code autoApply} (skip the review step), {@code statusMap} / {@code externalSystem} for reservations.
     */
    @Transactional
    public LoyaltyImportBatch start(Long tenantId, Long batchId, JsonNode columnMapping, JsonNode options) {
        LoyaltyImportBatch batch = require(tenantId, batchId);
        if (batch.getStatus() != LoyaltyImportBatch.Status.UPLOADED && batch.getStatus() != LoyaltyImportBatch.Status.VALIDATED
                && batch.getStatus() != LoyaltyImportBatch.Status.FAILED) {
            throw new IllegalStateException("Import is " + batch.getStatus() + "; it can only be (re)started after upload, validation or failure");
        }
        Long size = fileSize(batch)
                .orElseThrow(() -> new IllegalStateException("File not uploaded yet"));
        batch.setFileSize(size);
        if (columnMapping != null && columnMapping.isObject()) {
            batch.setColumnMapping(columnMapping);
        }
        if (options != null && options.isObject()) {
            batch.setOptions(options);
        }
        batch.setStatus(LoyaltyImportBatch.Status.QUEUED);
        batch.setError(null);
        batch.setRowsTotal(0);
        batch.setRowsValid(0);
        batch.setRowsInvalid(0);
        batch.setLeaseOwner(null);
        batch.setLeaseUntil(null);
        batch.setUpdatedAt(OffsetDateTime.now());
        return batchRepo.save(batch);
    }

    @Transactional
    public LoyaltyImportBatch apply(Long tenantId, Long batchId) {
        LoyaltyImportBatch batch = require(tenantId, batchId);
        if (batch.getStatus() != LoyaltyImportBatch.Status.VALIDATED) {
            throw new IllegalStateException("Only a VALIDATED import can be applied (now " + batch.getStatus() + ")");
        }
        if (batch.getRowsValid() == 0) {
            throw new IllegalStateException("No valid rows to apply");
        }
        batch.setStatus(LoyaltyImportBatch.Status.APPLY_QUEUED);
        batch.setUpdatedAt(OffsetDateTime.now());
        return batchRepo.save(batch);
    }

    @Transactional
    public LoyaltyImportBatch cancel(Long tenantId, Long batchId) {
        LoyaltyImportBatch batch = require(tenantId, batchId);
        if (!CANCELLABLE.contains(batch.getStatus())) {
            throw new IllegalStateException("Import is " + batch.getStatus() + " and can no longer be cancelled");
        }
        batch.setStatus(LoyaltyImportBatch.Status.CANCELLED);
        batch.setLeaseOwner(null);
        batch.setLeaseUntil(null);
        batch.setUpdatedAt(OffsetDateTime.now());
        discardFile(batchId);
        return batchRepo.save(batch);
    }

    /** CSV of every rejected row: row number, status, errors, then the original columns. */
    public String errorReport(Long tenantId, Long batchId) {
        LoyaltyImportBatch batch = require(tenantId, batchId);
        List<LoyaltyImportRow> rows = rowRepo.findByBatchIdAndStatusInOrderByRowNoAsc(batchId,
                List.of(LoyaltyImportRow.Status.INVALID, LoyaltyImportRow.Status.DUPLICATE, LoyaltyImportRow.Status.FAILED));
        StringWriter out = new StringWriter();
        List<String> headers = batch.getOptions().has("_headers")
                ? iterableValues(batch.getOptions().get("_headers"))
                : rows.isEmpty() ? List.of() : iterable(rows.get(0).getRaw());
        writeLine(out, concat(List.of("row", "status", "errors"), headers));
        for (LoyaltyImportRow r : rows) {
            String errors = r.getErrors() == null ? "" : String.join("; ", iterableValues(r.getErrors()));
            List<String> values = headers.stream().map(h -> r.getRaw().path(h).asText("")).toList();
            writeLine(out, concat(List.of(String.valueOf(r.getRowNo()), r.getStatus().name(), errors), values));
        }
        return out.toString();
    }

    ImportApplier applier(LoyaltyImportBatch.ImportType type) {
        ImportApplier a = appliers.get(type);
        if (a == null) {
            throw new IllegalArgumentException("Unsupported import type " + type);
        }
        return a;
    }

    private static List<String> iterable(JsonNode obj) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        obj.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static List<String> iterableValues(JsonNode arr) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static void writeLine(StringWriter out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.write(',');
            }
            String c = cells.get(i) == null ? "" : cells.get(i);
            if (c.contains(",") || c.contains("\"") || c.contains("\n") || c.contains("\r")) {
                out.write('"');
                out.write(c.replace("\"", "\"\""));
                out.write('"');
            } else {
                out.write(c);
            }
        }
        out.write("\r\n");
    }
}
