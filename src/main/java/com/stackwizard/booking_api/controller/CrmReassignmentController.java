package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.CrmTeamDtos;
import com.stackwizard.booking_api.model.CrmReassignment;
import com.stackwizard.booking_api.service.CrmReassignmentService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/crm/reassign")
public class CrmReassignmentController {
    private final CrmReassignmentService service;

    public CrmReassignmentController(CrmReassignmentService service) {
        this.service = service;
    }

    @GetMapping("/preview")
    public CrmTeamDtos.ReassignCounts preview(@RequestParam Long fromUserId) {
        return service.preview(fromUserId);
    }

    @PostMapping
    public CrmReassignment reassign(@RequestBody CrmTeamDtos.ReassignRequest request) {
        return service.reassign(request);
    }

    @GetMapping("/history")
    public List<CrmReassignment> history() {
        return service.history();
    }
}
