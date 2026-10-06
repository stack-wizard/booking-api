package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.CrmLeadConvertRequest;
import com.stackwizard.booking_api.dto.CrmStageChangeRequest;
import com.stackwizard.booking_api.dto.CrmTeamDtos;
import com.stackwizard.booking_api.model.CrmLead;
import com.stackwizard.booking_api.model.CrmOpportunity;
import com.stackwizard.booking_api.model.CrmStageTransition;
import com.stackwizard.booking_api.service.CrmLeadService;
import com.stackwizard.booking_api.service.CrmOpportunityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/crm")
public class CrmLeadOpportunityController {
    private final CrmLeadService leadService;
    private final CrmOpportunityService opportunityService;

    public CrmLeadOpportunityController(CrmLeadService leadService, CrmOpportunityService opportunityService) {
        this.leadService = leadService;
        this.opportunityService = opportunityService;
    }

    @GetMapping("/leads")
    public List<CrmLead> leads() {
        return leadService.findAll();
    }

    @GetMapping("/leads/{id}")
    public ResponseEntity<CrmLead> lead(@PathVariable Long id) {
        return leadService.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/leads")
    public ResponseEntity<CrmLead> createLead(@RequestBody CrmLead lead) {
        CrmLead saved = leadService.create(lead);
        return ResponseEntity.created(URI.create("/api/crm/leads/" + saved.getId())).body(saved);
    }

    @PutMapping("/leads/{id}")
    public CrmLead updateLead(@PathVariable Long id, @RequestBody CrmLead lead) {
        return leadService.update(id, lead);
    }

    @PostMapping("/leads/{id}/assign")
    public CrmLead assignLead(@PathVariable Long id, @RequestBody CrmTeamDtos.AssignRequest request) {
        return leadService.assign(id, request.ownerUserId(), request.teamId());
    }

    @PostMapping("/leads/{id}/auto-assign")
    public CrmLead autoAssignLead(@PathVariable Long id) {
        return leadService.autoAssign(id);
    }

    @PostMapping("/leads/{id}/convert")
    public CrmLead convert(@PathVariable Long id, @RequestBody(required = false) CrmLeadConvertRequest request) {
        return leadService.convert(id, request == null ? new CrmLeadConvertRequest() : request);
    }

    @GetMapping("/opportunities")
    public List<CrmOpportunity> opportunities(@RequestParam(required = false) Long pipelineId) {
        return opportunityService.findAll(pipelineId);
    }

    @GetMapping("/opportunities/{id}")
    public ResponseEntity<CrmOpportunity> opportunity(@PathVariable Long id) {
        return opportunityService.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/opportunities")
    public ResponseEntity<CrmOpportunity> createOpportunity(@RequestBody CrmOpportunity opportunity) {
        CrmOpportunity saved = opportunityService.create(opportunity);
        return ResponseEntity.created(URI.create("/api/crm/opportunities/" + saved.getId())).body(saved);
    }

    @PutMapping("/opportunities/{id}")
    public CrmOpportunity updateOpportunity(@PathVariable Long id, @RequestBody CrmOpportunity opportunity) {
        return opportunityService.update(id, opportunity);
    }

    @PutMapping("/opportunities/{id}/stage")
    public CrmOpportunity changeStage(@PathVariable Long id, @RequestBody CrmStageChangeRequest request) {
        return opportunityService.changeStage(id, request);
    }

    @GetMapping("/opportunities/{id}/transitions")
    public List<CrmStageTransition> transitions(@PathVariable Long id) {
        return opportunityService.transitions(id);
    }
}
