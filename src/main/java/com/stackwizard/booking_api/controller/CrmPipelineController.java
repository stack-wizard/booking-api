package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.CrmOutcomeReason;
import com.stackwizard.booking_api.model.CrmPipeline;
import com.stackwizard.booking_api.model.CrmPipelineStage;
import com.stackwizard.booking_api.model.CrmStageRequirement;
import com.stackwizard.booking_api.service.CrmPipelineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/crm")
public class CrmPipelineController {
    private final CrmPipelineService service;

    public CrmPipelineController(CrmPipelineService service) {
        this.service = service;
    }

    @GetMapping("/pipelines")
    public List<CrmPipeline> pipelines() {
        return service.findAll();
    }

    @GetMapping("/pipelines/{id}")
    public ResponseEntity<CrmPipeline> pipeline(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/pipelines")
    public ResponseEntity<CrmPipeline> createPipeline(@RequestBody CrmPipeline pipeline) {
        CrmPipeline saved = service.create(pipeline);
        return ResponseEntity.created(URI.create("/api/crm/pipelines/" + saved.getId())).body(saved);
    }

    @PutMapping("/pipelines/{id}")
    public CrmPipeline updatePipeline(@PathVariable Long id, @RequestBody CrmPipeline pipeline) {
        return service.update(id, pipeline);
    }

    @GetMapping("/pipelines/{id}/stages")
    public List<CrmPipelineStage> stages(@PathVariable Long id) {
        return service.stages(id);
    }

    @PostMapping("/pipelines/{id}/stages")
    public ResponseEntity<CrmPipelineStage> createStage(@PathVariable Long id, @RequestBody CrmPipelineStage stage) {
        CrmPipelineStage saved = service.createStage(id, stage);
        return ResponseEntity.created(URI.create("/api/crm/pipelines/" + id + "/stages/" + saved.getId())).body(saved);
    }

    @PutMapping("/stages/{stageId}")
    public CrmPipelineStage updateStage(@PathVariable Long stageId, @RequestBody CrmPipelineStage stage) {
        return service.updateStage(stageId, stage);
    }

    @GetMapping("/stages/{stageId}/requirements")
    public List<CrmStageRequirement> requirements(@PathVariable Long stageId) {
        return service.requirements(stageId);
    }

    @PostMapping("/stages/{stageId}/requirements")
    public ResponseEntity<CrmStageRequirement> createRequirement(@PathVariable Long stageId,
                                                                 @RequestBody CrmStageRequirement requirement) {
        CrmStageRequirement saved = service.createRequirement(stageId, requirement);
        return ResponseEntity.created(URI.create("/api/crm/stages/" + stageId + "/requirements/" + saved.getId())).body(saved);
    }

    @DeleteMapping("/requirements/{id}")
    public ResponseEntity<Void> deleteRequirement(@PathVariable Long id) {
        service.deleteRequirement(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/outcome-reasons")
    public List<CrmOutcomeReason> outcomeReasons() {
        return service.outcomeReasons();
    }

    @PostMapping("/outcome-reasons")
    public ResponseEntity<CrmOutcomeReason> createOutcome(@RequestBody CrmOutcomeReason reason) {
        CrmOutcomeReason saved = service.createOutcomeReason(reason);
        return ResponseEntity.created(URI.create("/api/crm/outcome-reasons/" + saved.getId())).body(saved);
    }

    @PutMapping("/outcome-reasons/{id}")
    public CrmOutcomeReason updateOutcome(@PathVariable Long id, @RequestBody CrmOutcomeReason reason) {
        return service.updateOutcomeReason(id, reason);
    }
}
