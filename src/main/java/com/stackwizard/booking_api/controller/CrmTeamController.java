package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.CrmTeam;
import com.stackwizard.booking_api.model.CrmTeamMember;
import com.stackwizard.booking_api.model.CrmTeamProperty;
import com.stackwizard.booking_api.dto.PlatformUserDtos;
import com.stackwizard.booking_api.service.CrmPlatformUserService;
import com.stackwizard.booking_api.service.CrmTeamService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/crm/teams")
public class CrmTeamController {
    private final CrmTeamService service;
    private final CrmPlatformUserService platformUsers;

    public CrmTeamController(CrmTeamService service, CrmPlatformUserService platformUsers) {
        this.service = service;
        this.platformUsers = platformUsers;
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

    /** Mikos Platform users of the chain and of the team's hotels, to pick new members from. */
    @GetMapping("/{id}/platform-users")
    public List<PlatformUserDtos.PlatformUserDto> platformUsers(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return platformUsers.list(id, jwt.getTokenValue());
    }

    /** Adds a Platform user to the team; the booking user is created when the person has not logged in yet. */
    @PostMapping("/{id}/members/platform")
    public ResponseEntity<CrmTeamMember> addPlatformMember(@PathVariable Long id,
                                                           @RequestBody PlatformUserDtos.AddPlatformMemberRequest request,
                                                           @AuthenticationPrincipal Jwt jwt) {
        CrmTeamMember saved = platformUsers.addMember(id, request.platformUserId(), request.teamLead(),
                request.validFrom(), request.validTo(), jwt.getTokenValue());
        return ResponseEntity.created(URI.create("/api/crm/teams/" + id + "/members/" + saved.getId())).body(saved);
    }

    @PutMapping("/members/{memberId}")
    public CrmTeamMember updateMember(@PathVariable Long memberId, @RequestBody CrmTeamMember member) {
        return service.updateMember(memberId, member);
    }

    /** Hotels per team; a team without rows works for every hotel. */
    @GetMapping("/properties")
    public List<CrmTeamProperty> properties() {
        return service.allProperties();
    }

    @PutMapping("/{id}/properties")
    public List<CrmTeamProperty> replaceProperties(@PathVariable Long id, @RequestBody List<Long> propertyTenantIds) {
        return service.replaceProperties(id, propertyTenantIds);
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
