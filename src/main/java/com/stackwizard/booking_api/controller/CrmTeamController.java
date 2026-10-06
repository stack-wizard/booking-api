package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.CrmTeam;
import com.stackwizard.booking_api.model.CrmTeamMember;
import com.stackwizard.booking_api.service.CrmTeamService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/crm/teams")
public class CrmTeamController {
    private final CrmTeamService service;

    public CrmTeamController(CrmTeamService service) {
        this.service = service;
    }

    @GetMapping
    public List<CrmTeam> all() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<CrmTeam> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<CrmTeam> create(@RequestBody CrmTeam team) {
        CrmTeam saved = service.create(team);
        return ResponseEntity.created(URI.create("/api/crm/teams/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public CrmTeam update(@PathVariable Long id, @RequestBody CrmTeam team) {
        return service.update(id, team);
    }

    @GetMapping("/{id}/members")
    public List<CrmTeamMember> members(@PathVariable Long id) {
        return service.members(id);
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<CrmTeamMember> addMember(@PathVariable Long id, @RequestBody CrmTeamMember member) {
        CrmTeamMember saved = service.addMember(id, member);
        return ResponseEntity.created(URI.create("/api/crm/teams/" + id + "/members/" + saved.getId())).body(saved);
    }

    @PutMapping("/members/{memberId}")
    public CrmTeamMember updateMember(@PathVariable Long memberId, @RequestBody CrmTeamMember member) {
        return service.updateMember(memberId, member);
    }

    @GetMapping("/users")
    public List<CrmTeamService.CrmUser> users() {
        return service.users();
    }

    @GetMapping("/my-scope")
    public CrmTeamService.MyScope myScope() {
        return service.myScope();
    }

    @DeleteMapping("/members/{memberId}")
    public ResponseEntity<Void> removeMember(@PathVariable Long memberId) {
        service.removeMember(memberId);
        return ResponseEntity.noContent().build();
    }
}
