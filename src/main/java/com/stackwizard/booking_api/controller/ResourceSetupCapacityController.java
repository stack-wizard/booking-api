package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.ResourceSetupCapacity;
import com.stackwizard.booking_api.service.ResourceSetupCapacityService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api")
public class ResourceSetupCapacityController {
    private final ResourceSetupCapacityService service;

    public ResourceSetupCapacityController(ResourceSetupCapacityService service) {
        this.service = service;
    }

    @GetMapping("/resources/setup-capacities")
    public List<ResourceSetupCapacity> all() {
        return service.all();
    }

    @GetMapping("/resources/{id}/setup-capacities")
    public List<ResourceSetupCapacity> forResource(@PathVariable Long id) {
        return service.forResource(id);
    }

    @PutMapping("/resources/{id}/setup-capacities")
    public List<ResourceSetupCapacity> replace(@PathVariable Long id, @RequestBody List<ResourceSetupCapacity> setups) {
        return service.replace(id, setups);
    }

    @GetMapping("/crm/spaces")
    public List<ResourceSetupCapacityService.SpaceCandidate> spaces(
            @RequestParam(required = false) Integer minPax,
            @RequestParam(required = false) ResourceSetupCapacity.SetupStyle setupStyle,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return service.searchSpaces(minPax, setupStyle, from, to);
    }
}
