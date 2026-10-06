package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.CrmAlert;
import com.stackwizard.booking_api.service.CrmAlertService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/crm/alerts")
public class CrmAlertController {
    private final CrmAlertService service;

    public CrmAlertController(CrmAlertService service) {
        this.service = service;
    }

    @GetMapping
    public List<CrmAlert> open() {
        return service.openForMe();
    }

    @PostMapping("/{id}/ack")
    public CrmAlert acknowledge(@PathVariable Long id) {
        return service.acknowledge(id);
    }
}
