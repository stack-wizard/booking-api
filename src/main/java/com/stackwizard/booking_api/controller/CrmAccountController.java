package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.CrmTeamDtos;
import com.stackwizard.booking_api.model.CrmAccount;
import com.stackwizard.booking_api.model.CrmAccountContactRole;
import com.stackwizard.booking_api.model.CrmAccountRelation;
import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.service.CrmAccountRelationService;
import com.stackwizard.booking_api.service.CrmAccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/crm/accounts")
public class CrmAccountController {
    private final CrmAccountService service;
    private final CrmAccountRelationService relationService;

    public CrmAccountController(CrmAccountService service, CrmAccountRelationService relationService) {
        this.service = service;
        this.relationService = relationService;
    }

    @GetMapping("/{id}/relations")
    public List<CrmTeamDtos.RelationView> relations(@PathVariable Long id) {
        return relationService.forAccount(id);
    }

    @PostMapping("/{id}/relations")
    public CrmAccountRelation addRelation(@PathVariable Long id, @RequestBody CrmTeamDtos.RelationRequest request) {
        return relationService.create(id, request);
    }

    @DeleteMapping("/relations/{relationId}")
    public ResponseEntity<Void> deleteRelation(@PathVariable Long relationId) {
        relationService.delete(relationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public List<CrmAccount> search(@RequestParam(required = false) String search,
                                   @RequestParam(required = false) CrmAccount.AccountType accountType,
                                   @RequestParam(required = false) String segment,
                                   @RequestParam(required = false) Long ownerUserId,
                                   @RequestParam(required = false) Boolean active) {
        return service.search(search, accountType, segment, ownerUserId, active);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CrmAccount> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<CrmAccount> create(@RequestBody CrmAccount account) {
        CrmAccount saved = service.create(account);
        return ResponseEntity.created(URI.create("/api/crm/accounts/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public CrmAccount update(@PathVariable Long id, @RequestBody CrmAccount account) {
        return service.update(id, account);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.softDelete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/contacts")
    public List<CrmContact> contacts(@PathVariable Long id) {
        return service.contactsForAccount(id);
    }

    @PostMapping("/{id}/contacts/{contactId}/roles")
    public ResponseEntity<CrmAccountContactRole> addRole(@PathVariable Long id,
                                                         @PathVariable Long contactId,
                                                         @RequestBody CrmAccountContactRole role) {
        CrmAccountContactRole saved = service.addRole(id, contactId, role);
        return ResponseEntity.created(URI.create("/api/crm/accounts/" + id + "/contacts/" + contactId + "/roles/" + saved.getId()))
                .body(saved);
    }

    @DeleteMapping("/roles/{roleId}")
    public ResponseEntity<Void> deleteRole(@PathVariable Long roleId) {
        service.deleteRole(roleId);
        return ResponseEntity.noContent().build();
    }
}
