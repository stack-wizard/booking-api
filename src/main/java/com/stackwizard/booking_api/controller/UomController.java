package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.Uom;
import com.stackwizard.booking_api.repository.UomRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/uoms")
public class UomController {
    private final UomRepository repo;

    public UomController(UomRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<Uom> all() {
        return repo.findByActiveTrue();
    }
}
