package com.stackwizard.booking_api.service.loyalty.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.LoyaltyImportBatch;
import com.stackwizard.booking_api.model.LoyaltyImportRow;
import com.stackwizard.booking_api.repository.LoyaltyImportBatchRepository;
import com.stackwizard.booking_api.repository.LoyaltyImportRowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.net.InetAddress;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Runs imports on whichever instance claims them. A batch is leased (2 min, renewed per chunk); if the instance
 * dies the lease expires and another one resumes: staging restarts from scratch (it only fills staging rows),
 * applying continues after {@code last_applied_row}. Rows apply one transaction each, so a bad row is recorded
 * as FAILED instead of rolling back its neighbours.
 */
@Service
public class LoyaltyImportWorker {
    private static final Logger log = LoggerFactory.getLogger(LoyaltyImportWorker.class);
    static final int STAGE_CHUNK = 1000;
    static final int APPLY_CHUNK = 200;

    private final LoyaltyImportBatchRepository batchRepo;
    private final LoyaltyImportRowRepository rowRepo;
    private final LoyaltyImportService importService;
    private final ImportStorage storage;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final String owner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LoyaltyImportWorker(LoyaltyImportBatchRepository batchRepo,
                               LoyaltyImportRowRepository rowRepo,
                               LoyaltyImportService importService,
                               ImportStorage storage,
                               JdbcTemplate jdbc,
                               TransactionTemplate tx,
                               @Value("${loyalty.import.enabled:true}") boolean enabled) {
        this.batchRepo = batchRepo;
        this.rowRepo = rowRepo;
        this.importService = importService;
        this.storage = storage;
        this.jdbc = jdbc;
        this.tx = tx;
        this.enabled = enabled;
        this.owner = hostName() + "/" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Scheduled(fixedDelayString = "${loyalty.import.poll-ms:5000}", initialDelayString = "${loyalty.import.initial-delay-ms:20000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        drain();
    }

    /** Claims and runs batches until none is left for this instance. */
    public void drain() {
        while (true) {
            List<Long> claimed = tx.execute(s -> batchRepo.claimNext(owner));
            if (claimed == null || claimed.isEmpty()) {
                return;
            }
            process(claimed.get(0));
        }
    }

    public void process(Long batchId) {
        LoyaltyImportBatch batch = batchRepo.findById(batchId).orElse(null);
        if (batch == null) {
            return;
        }
        try {
            switch (batch.getStatus()) {
                case QUEUED, STAGING -> stageAndValidate(batch);
                case APPLY_QUEUED, APPLYING -> applyAll(batch);
                default -> release(batch.getId());
            }
        } catch (LeaseLostException ex) {
            log.warn("Import {} lease lost; another instance will continue", batchId);
        } catch (Exception ex) {
            log.error("Import {} failed", batchId, ex);
            fail(batchId, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    // --- staging + validation -------------------------------------------------------------------------------

    void stageAndValidate(LoyaltyImportBatch batch) throws Exception {
        ImportApplier applier = importService.applier(batch.getImportType());
        update(batch.getId(), b -> b.setStatus(LoyaltyImportBatch.Status.STAGING));
        tx.executeWithoutResult(s -> rowRepo.deleteAfter(batch.getId(), 0));

        JsonNode options = batch.getOptions();
        Character delimiter = options.hasNonNull("delimiter") && !options.get("delimiter").asText().isEmpty()
                ? decodeDelimiter(options.get("delimiter").asText()) : null;
        int rowNo = 0;
        Map<String, String> mapping;
        try (InputStream in = storage.open(batch.getS3Key()); CsvStreamReader csv = new CsvStreamReader(in, delimiter)) {
            List<String> header = csv.next();
            if (header == null) {
                throw new IllegalStateException("File is empty");
            }
            List<String> headers = uniqueHeaders(header);
            mapping = resolveMapping(applier.fields(), headers, batch.getColumnMapping());
            List<String> missing = missingRequired(applier, mapping);
            if (!missing.isEmpty()) {
                throw new IllegalStateException("Missing required column(s): " + String.join(", ", missing)
                        + "; found: " + String.join(", ", headers));
            }
            ObjectNode resolved = objectMapper.valueToTree(mapping);
            // jsonb reorders keys; keep the file's column order for the error report
            ArrayNode headerOrder = objectMapper.valueToTree(headers);
            update(batch.getId(), b -> {
                b.setColumnMapping(resolved);
                ((ObjectNode) b.getOptions()).set("_headers", headerOrder);
            });
            List<Object[]> chunk = new ArrayList<>(STAGE_CHUNK);
            List<String> record;
            while ((record = csv.next()) != null) {
                rowNo++;
                ObjectNode raw = objectMapper.createObjectNode();
                for (int i = 0; i < headers.size(); i++) {
                    raw.put(headers.get(i), i < record.size() ? record.get(i) : "");
                }
                chunk.add(new Object[]{batch.getTenantId(), batch.getId(), rowNo, raw.toString()});
                if (chunk.size() == STAGE_CHUNK) {
                    insertRows(chunk);
                    chunk.clear();
                    heartbeat(batch.getId());
                }
            }
            if (!chunk.isEmpty()) {
                insertRows(chunk);
            }
        }
        int total = rowNo;
        update(batch.getId(), b -> b.setRowsTotal(total));
        validate(batch, applier, mapping);
    }

    private void validate(LoyaltyImportBatch batch, ImportApplier applier, Map<String, String> mapping) {
        Object state = applier.newValidationState(batch);
        String dateFormat = batch.getOptions().path("dateFormat").asText(null);
        int after = 0;
        int valid = 0;
        int invalid = 0;
        while (true) {
            List<LoyaltyImportRow> rows = rowRepo.findChunk(batch.getId(), after, PageRequest.of(0, STAGE_CHUNK));
            if (rows.isEmpty()) {
                break;
            }
            List<Object[]> updates = new ArrayList<>(rows.size());
            for (LoyaltyImportRow r : rows) {
                ImportRow row = new ImportRow(r.getRaw(), mapping, dateFormat);
                String duplicate = applier.validate(batch, row, state);
                LoyaltyImportRow.Status status;
                if (!row.errors().isEmpty()) {
                    status = LoyaltyImportRow.Status.INVALID;
                    invalid++;
                } else if (duplicate != null) {
                    status = LoyaltyImportRow.Status.DUPLICATE;
                    row.error(duplicate);
                    invalid++;
                } else {
                    status = LoyaltyImportRow.Status.VALID;
                    valid++;
                }
                updates.add(new Object[]{status.name(), row.errors().isEmpty() ? null : errorsJson(row.errors()), r.getId()});
                after = r.getRowNo();
            }
            jdbc.batchUpdate("update loyalty_import_row set status = ?, errors = ?::jsonb where id = ?", updates);
            heartbeat(batch.getId());
        }
        int v = valid;
        int i = invalid;
        boolean autoApply = batch.getOptions().path("autoApply").asBoolean(false) && v > 0;
        update(batch.getId(), b -> {
            b.setRowsValid(v);
            b.setRowsInvalid(i);
            b.setStatus(autoApply ? LoyaltyImportBatch.Status.APPLY_QUEUED : LoyaltyImportBatch.Status.VALIDATED);
            b.setLeaseOwner(null);
            b.setLeaseUntil(null);
        });
    }

    // --- apply ----------------------------------------------------------------------------------------------

    void applyAll(LoyaltyImportBatch batch) {
        ImportApplier applier = importService.applier(batch.getImportType());
        update(batch.getId(), b -> b.setStatus(LoyaltyImportBatch.Status.APPLYING));
        LoyaltyImportBatch current = batchRepo.findById(batch.getId()).orElseThrow();
        Map<String, String> mapping = toMap(current.getColumnMapping());
        String dateFormat = current.getOptions().path("dateFormat").asText(null);
        int after = current.getLastAppliedRow();
        int applied = current.getRowsApplied();
        while (true) {
            List<LoyaltyImportRow> rows = rowRepo.findChunk(batch.getId(), after, PageRequest.of(0, APPLY_CHUNK));
            if (rows.isEmpty()) {
                break;
            }
            for (LoyaltyImportRow r : rows) {
                after = r.getRowNo();
                if (r.getStatus() != LoyaltyImportRow.Status.VALID) {
                    continue;
                }
                ImportRow row = new ImportRow(r.getRaw(), mapping, dateFormat);
                try {
                    Long target = tx.execute(s -> {
                        Long id = applier.apply(current, row);
                        jdbc.update("update loyalty_import_row set status = 'APPLIED', target_id = ? where id = ?", id, r.getId());
                        return id;
                    });
                    applied++;
                } catch (Exception ex) {
                    String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                    jdbc.update("update loyalty_import_row set status = 'FAILED', errors = ?::jsonb where id = ?",
                            errorsJson(List.of(msg)), r.getId());
                }
            }
            int checkpoint = after;
            int appliedSoFar = applied;
            update(batch.getId(), b -> {
                b.setLastAppliedRow(checkpoint);
                b.setRowsApplied(appliedSoFar);
            });
            heartbeat(batch.getId());
        }
        tx.executeWithoutResult(s -> applier.afterApply(current));
        update(batch.getId(), b -> {
            b.setStatus(LoyaltyImportBatch.Status.DONE);
            b.setLeaseOwner(null);
            b.setLeaseUntil(null);
        });
    }

    // --- helpers --------------------------------------------------------------------------------------------

    /** Explicit mapping wins; otherwise a header matches a field by its normalised name or an alias. */
    static Map<String, String> resolveMapping(Map<String, List<String>> fields, List<String> headers, JsonNode explicit) {
        Map<String, String> byNorm = new HashMap<>();
        for (String h : headers) {
            byNorm.putIfAbsent(norm(h), h);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> f : fields.entrySet()) {
            String field = f.getKey();
            if (explicit != null && explicit.hasNonNull(field) && !explicit.get(field).asText().isBlank()) {
                String wanted = explicit.get(field).asText();
                String header = headers.contains(wanted) ? wanted : byNorm.get(norm(wanted));
                if (header != null) {
                    out.put(field, header);
                }
                continue;
            }
            List<String> candidates = new ArrayList<>();
            candidates.add(field.replace("*", ""));
            candidates.addAll(f.getValue());
            for (String c : candidates) {
                String header = byNorm.get(norm(c));
                if (header != null) {
                    out.put(field, header);
                    break;
                }
            }
        }
        return out;
    }

    static List<String> missingRequired(ImportApplier applier, Map<String, String> mapping) {
        List<String> missing = new ArrayList<>();
        if (applier instanceof RequiredFields rf) {
            for (String f : rf.requiredFields()) {
                if (!mapping.containsKey(f)) {
                    missing.add(f);
                }
            }
        }
        return missing;
    }

    static String norm(String s) {
        String folded = Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return folded.replaceAll("[^a-z0-9]", "");
    }

    static List<String> uniqueHeaders(List<String> header) {
        List<String> out = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i).trim();
            if (h.isEmpty()) {
                h = "column_" + (i + 1);
            }
            int n = seen.merge(h, 1, Integer::sum);
            out.add(n == 1 ? h : h + "_" + n);
        }
        return out;
    }

    private static Character decodeDelimiter(String d) {
        return switch (d) {
            case "\\t", "tab", "TAB" -> '\t';
            default -> d.charAt(0);
        };
    }

    private void insertRows(List<Object[]> chunk) {
        jdbc.batchUpdate("insert into loyalty_import_row (tenant_id, batch_id, row_no, raw, status) values (?, ?, ?, ?::jsonb, 'STAGED')", chunk);
    }

    private String errorsJson(List<String> errors) {
        ArrayNode arr = objectMapper.createArrayNode();
        errors.forEach(arr::add);
        return arr.toString();
    }

    private Map<String, String> toMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node != null) {
            node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        }
        return out;
    }

    private void heartbeat(Long batchId) {
        Integer n = tx.execute(s -> batchRepo.heartbeat(batchId, owner));
        if (n == null || n == 0) {
            throw new LeaseLostException();
        }
        LoyaltyImportBatch.Status status = batchRepo.findById(batchId).map(LoyaltyImportBatch::getStatus).orElse(null);
        if (status == LoyaltyImportBatch.Status.CANCELLED) {
            throw new LeaseLostException();
        }
    }

    private void update(Long batchId, java.util.function.Consumer<LoyaltyImportBatch> change) {
        tx.executeWithoutResult(s -> {
            LoyaltyImportBatch b = batchRepo.findById(batchId).orElseThrow();
            change.accept(b);
            b.setUpdatedAt(OffsetDateTime.now());
            batchRepo.save(b);
        });
    }

    private void release(Long batchId) {
        update(batchId, b -> {
            b.setLeaseOwner(null);
            b.setLeaseUntil(null);
        });
    }

    private void fail(Long batchId, String error) {
        try {
            update(batchId, b -> {
                if (b.getStatus() == LoyaltyImportBatch.Status.CANCELLED) {
                    return;
                }
                b.setStatus(LoyaltyImportBatch.Status.FAILED);
                b.setError(error == null ? null : error.length() > 2000 ? error.substring(0, 2000) : error);
                b.setLeaseOwner(null);
                b.setLeaseUntil(null);
            });
        } catch (Exception ex) {
            log.error("Could not mark import {} failed", batchId, ex);
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            return "booking-api";
        }
    }

    static final class LeaseLostException extends RuntimeException {
        LeaseLostException() {
            super("lease lost", null, false, false);
        }
    }
}
