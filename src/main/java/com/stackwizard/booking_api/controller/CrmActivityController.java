package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.CrmActivity;
import com.stackwizard.booking_api.model.CrmAttachment;
import com.stackwizard.booking_api.service.CrmActivityService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/crm")
public class CrmActivityController {
    private final CrmActivityService service;

    public CrmActivityController(CrmActivityService service) {
        this.service = service;
    }

    @GetMapping("/activities")
    public List<CrmActivity> search(@RequestParam(required = false) Long accountId,
                                    @RequestParam(required = false) Long opportunityId,
                                    @RequestParam(required = false) Long leadId,
                                    @RequestParam(required = false) Long assignedTo,
                                    @RequestParam(required = false, defaultValue = "false") boolean openOnly) {
        return service.search(accountId, opportunityId, leadId, assignedTo, openOnly);
    }

    @GetMapping("/activities/{id}")
    public ResponseEntity<CrmActivity> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/activities")
    public ResponseEntity<CrmActivity> create(@RequestBody CrmActivity activity) {
        CrmActivity saved = service.create(activity);
        return ResponseEntity.created(URI.create("/api/crm/activities/" + saved.getId())).body(saved);
    }

    @PutMapping("/activities/{id}")
    public CrmActivity update(@PathVariable Long id, @RequestBody CrmActivity activity) {
        return service.update(id, activity);
    }

    @PostMapping("/activities/{id}/complete")
    public CrmActivity complete(@PathVariable Long id) {
        return service.complete(id);
    }

    @GetMapping("/accounts/{accountId}/attachments")
    public List<CrmAttachment> attachments(@PathVariable Long accountId) {
        return service.listAttachments(accountId);
    }

    @PostMapping(value = "/accounts/{accountId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CrmAttachment> upload(@PathVariable Long accountId, @RequestParam("file") MultipartFile file) {
        CrmAttachment saved = service.uploadAttachment(accountId, file);
        return ResponseEntity.created(URI.create("/api/crm/accounts/" + accountId + "/attachments/" + saved.getId())).body(saved);
    }

    @DeleteMapping("/attachments/{attachmentId}")
    public ResponseEntity<Void> deleteAttachment(@PathVariable Long attachmentId) {
        service.deleteAttachment(attachmentId);
        return ResponseEntity.noContent().build();
    }
}
