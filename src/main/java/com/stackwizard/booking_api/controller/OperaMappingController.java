package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.OperaDtos;
import com.stackwizard.booking_api.model.OperaCode;
import com.stackwizard.booking_api.model.OperaCodeMap;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.service.opera.OperaMappingService;
import com.stackwizard.booking_api.service.opera.OperaRoomTypeSyncService;
import com.stackwizard.booking_api.service.opera.OperaSetupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/opera/hotels")
public class OperaMappingController {
    private final OperaMappingService service;
    private final OperaSetupService setupService;

    public OperaMappingController(OperaMappingService service, OperaSetupService setupService) {
        this.service = service;
        this.setupService = setupService;
    }

    @PostMapping("/{id}/setup")
    public OperaDtos.SetupResult setup(@PathVariable Long id) {
        return setupService.setup(id);
    }

    @GetMapping
    public List<OperaHotel> hotels() {
        return service.hotels();
    }

    @PutMapping("/{id}/modes")
    public OperaHotel updateModes(@PathVariable Long id, @RequestBody OperaDtos.ModesRequest request) {
        return service.updateModes(id, request);
    }

    @GetMapping("/{id}/codes")
    public List<OperaCode> codes(@PathVariable Long id, @RequestParam OperaCode.Kind kind) {
        return service.codes(id, kind);
    }

    @PostMapping("/{id}/codes/import")
    public List<OperaDtos.ImportKindResult> importCodes(@PathVariable Long id) {
        return service.importCodes(id);
    }

    @PostMapping("/{id}/room-types/sync")
    public OperaRoomTypeSyncService.SyncResult syncRoomTypes(@PathVariable Long id) {
        return service.syncRoomTypes(id);
    }

    @GetMapping("/{id}/maps")
    public List<OperaCodeMap> maps(@PathVariable Long id) {
        return service.maps(id);
    }

    @PutMapping("/{id}/maps")
    public ResponseEntity<OperaCodeMap> saveMap(@PathVariable Long id, @RequestBody OperaDtos.MapRequest request) {
        return service.saveMap(id, request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
