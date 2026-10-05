package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwizard.booking_api.model.CrmCustomFieldDefinition;
import com.stackwizard.booking_api.repository.CrmCustomFieldDefinitionRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class CrmCustomFieldDefinitionService {
    private final CrmCustomFieldDefinitionRepository repo;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CrmAccessContext accessContext;

    public CrmCustomFieldDefinitionService(CrmCustomFieldDefinitionRepository repo, CrmAccessContext accessContext) {
        this.repo = repo;
        this.accessContext = accessContext;
    }

    public List<CrmCustomFieldDefinition> findAll() {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        return repo.findByTenantIdOrderByDisplayOrderAsc(TenantResolver.requireTenantId());
    }

    public Optional<CrmCustomFieldDefinition> findById(Long id) {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        return repo.findByIdAndTenantId(id, TenantResolver.requireTenantId());
    }

    @Transactional
    public CrmCustomFieldDefinition create(CrmCustomFieldDefinition def) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        def.setId(null);
        def.setTenantId(TenantResolver.requireTenantId());
        if (def.getRequired() == null) {
            def.setRequired(false);
        }
        if (def.getDisplayOrder() == null) {
            def.setDisplayOrder(0);
        }
        if (def.getActive() == null) {
            def.setActive(true);
        }
        if (def.getOptions() == null) {
            def.setOptions(objectMapper.createArrayNode());
        }
        return repo.save(def);
    }

    @Transactional
    public CrmCustomFieldDefinition update(Long id, CrmCustomFieldDefinition changes) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        CrmCustomFieldDefinition existing = repo.findByIdAndTenantId(id, TenantResolver.requireTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Custom field not found: " + id));
        existing.setEntity(changes.getEntity());
        existing.setFieldKey(changes.getFieldKey());
        existing.setLabel(changes.getLabel());
        existing.setFieldType(changes.getFieldType());
        if (changes.getOptions() != null) {
            existing.setOptions(changes.getOptions());
        }
        if (changes.getRequired() != null) {
            existing.setRequired(changes.getRequired());
        }
        if (changes.getDisplayOrder() != null) {
            existing.setDisplayOrder(changes.getDisplayOrder());
        }
        if (changes.getActive() != null) {
            existing.setActive(changes.getActive());
        }
        return repo.save(existing);
    }
}
