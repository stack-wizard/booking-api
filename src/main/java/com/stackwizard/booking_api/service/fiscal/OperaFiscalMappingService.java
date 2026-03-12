package com.stackwizard.booking_api.service.fiscal;

import com.stackwizard.booking_api.model.OperaFiscalChargeMapping;
import com.stackwizard.booking_api.model.OperaFiscalPaymentMapping;
import com.stackwizard.booking_api.model.OperaFiscalTaxMapping;
import com.stackwizard.booking_api.model.OperaFiscalUdfMapping;
import com.stackwizard.booking_api.repository.OperaFiscalChargeMappingRepository;
import com.stackwizard.booking_api.repository.OperaFiscalPaymentMappingRepository;
import com.stackwizard.booking_api.repository.OperaFiscalTaxMappingRepository;
import com.stackwizard.booking_api.repository.OperaFiscalUdfMappingRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class OperaFiscalMappingService {
    private final OperaFiscalChargeMappingRepository chargeRepo;
    private final OperaFiscalPaymentMappingRepository paymentRepo;
    private final OperaFiscalTaxMappingRepository taxRepo;
    private final OperaFiscalUdfMappingRepository udfRepo;

    public OperaFiscalMappingService(OperaFiscalChargeMappingRepository chargeRepo,
                                     OperaFiscalPaymentMappingRepository paymentRepo,
                                     OperaFiscalTaxMappingRepository taxRepo,
                                     OperaFiscalUdfMappingRepository udfRepo) {
        this.chargeRepo = chargeRepo;
        this.paymentRepo = paymentRepo;
        this.taxRepo = taxRepo;
        this.udfRepo = udfRepo;
    }

    public Optional<OperaFiscalChargeMapping> resolveChargeMapping(Long tenantId, Long productId, String productType) {
        if (tenantId == null) {
            return Optional.empty();
        }
        if (productId != null) {
            Optional<OperaFiscalChargeMapping> byProduct = chargeRepo
                    .findFirstByTenantIdAndActiveTrueAndProductIdOrderByPriorityAsc(tenantId, productId);
            if (byProduct.isPresent()) {
                return byProduct;
            }
        }
        if (productType != null && !productType.isBlank()) {
            Optional<OperaFiscalChargeMapping> byProductType = chargeRepo
                    .findFirstByTenantIdAndActiveTrueAndProductIdIsNullAndProductTypeIgnoreCaseOrderByPriorityAsc(
                            tenantId,
                            productType.trim()
                    );
            if (byProductType.isPresent()) {
                return byProductType;
            }
        }
        return chargeRepo.findFirstByTenantIdAndActiveTrueAndProductIdIsNullAndProductTypeIsNullOrderByPriorityAsc(tenantId);
    }

    public Optional<OperaFiscalPaymentMapping> resolvePaymentMapping(Long tenantId, String paymentType) {
        if (tenantId == null || paymentType == null || paymentType.isBlank()) {
            return Optional.empty();
        }
        return paymentRepo.findByTenantIdAndPaymentTypeIgnoreCase(tenantId, paymentType.trim())
                .filter(m -> Boolean.TRUE.equals(m.getActive()));
    }

    public Optional<OperaFiscalTaxMapping> resolveTaxMapping(Long tenantId, BigDecimal taxPercent) {
        if (tenantId == null || taxPercent == null) {
            return Optional.empty();
        }
        BigDecimal normalized = taxPercent.setScale(4, java.math.RoundingMode.HALF_UP);
        return taxRepo.findByTenantIdAndTaxPercent(tenantId, normalized)
                .filter(m -> Boolean.TRUE.equals(m.getActive()));
    }

    public List<OperaFiscalUdfMapping> activeUdfMappings(Long tenantId) {
        if (tenantId == null) {
            return List.of();
        }
        return udfRepo.findByTenantIdAndActiveTrueOrderBySortOrderAscIdAsc(tenantId);
    }

    public List<OperaFiscalChargeMapping> findChargeMappings(Long tenantId) {
        return tenantId == null ? chargeRepo.findAll() : chargeRepo.findByTenantId(tenantId);
    }

    public List<OperaFiscalPaymentMapping> findPaymentMappings(Long tenantId) {
        return tenantId == null ? paymentRepo.findAll() : paymentRepo.findByTenantId(tenantId);
    }

    public List<OperaFiscalTaxMapping> findTaxMappings(Long tenantId) {
        return tenantId == null ? taxRepo.findAll() : taxRepo.findByTenantId(tenantId);
    }

    public List<OperaFiscalUdfMapping> findUdfMappings(Long tenantId) {
        return tenantId == null ? udfRepo.findAll() : udfRepo.findByTenantId(tenantId);
    }

    public OperaFiscalChargeMapping saveChargeMapping(OperaFiscalChargeMapping mapping) {
        if (mapping == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (mapping.getTenantId() == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (mapping.getTrxCode() == null || mapping.getTrxCode().isBlank()) {
            throw new IllegalArgumentException("trxCode is required");
        }
        mapping.setTrxCode(normalizeUpper(mapping.getTrxCode()));
        mapping.setTrxType(normalizeUpperDefault(mapping.getTrxType(), "C"));
        mapping.setTrxCodeType(normalizeUpperDefault(mapping.getTrxCodeType(), "L"));
        mapping.setTrxGroup(normalizeNullable(mapping.getTrxGroup()));
        mapping.setTrxSubGroup(normalizeNullable(mapping.getTrxSubGroup()));
        mapping.setProductType(normalizeNullableUpper(mapping.getProductType()));
        if (mapping.getPriority() == null) {
            mapping.setPriority(100);
        }
        if (mapping.getActive() == null) {
            mapping.setActive(Boolean.TRUE);
        }
        return chargeRepo.save(mapping);
    }

    public OperaFiscalPaymentMapping savePaymentMapping(OperaFiscalPaymentMapping mapping) {
        if (mapping == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (mapping.getTenantId() == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (mapping.getPaymentType() == null || mapping.getPaymentType().isBlank()) {
            throw new IllegalArgumentException("paymentType is required");
        }
        if (mapping.getTrxCode() == null || mapping.getTrxCode().isBlank()) {
            throw new IllegalArgumentException("trxCode is required");
        }
        mapping.setPaymentType(normalizeUpper(mapping.getPaymentType()));
        mapping.setTrxCode(normalizeUpper(mapping.getTrxCode()));
        mapping.setTrxType(normalizeUpperDefault(mapping.getTrxType(), "FC"));
        mapping.setTrxCodeType(normalizeUpperDefault(mapping.getTrxCodeType(), "O"));
        mapping.setTrxGroup(normalizeNullable(mapping.getTrxGroup()));
        mapping.setTrxSubGroup(normalizeNullable(mapping.getTrxSubGroup()));
        if (mapping.getActive() == null) {
            mapping.setActive(Boolean.TRUE);
        }
        return paymentRepo.save(mapping);
    }

    public OperaFiscalTaxMapping saveTaxMapping(OperaFiscalTaxMapping mapping) {
        if (mapping == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (mapping.getTenantId() == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (mapping.getTaxPercent() == null) {
            throw new IllegalArgumentException("taxPercent is required");
        }
        if (mapping.getGenerateTrxCode() == null || mapping.getGenerateTrxCode().isBlank()) {
            throw new IllegalArgumentException("generateTrxCode is required");
        }
        mapping.setTaxPercent(mapping.getTaxPercent().setScale(4, java.math.RoundingMode.HALF_UP));
        mapping.setGenerateTrxCode(normalizeUpper(mapping.getGenerateTrxCode()));
        mapping.setTaxName(normalizeNullable(mapping.getTaxName()));
        if (mapping.getActive() == null) {
            mapping.setActive(Boolean.TRUE);
        }
        return taxRepo.save(mapping);
    }

    public OperaFiscalUdfMapping saveUdfMapping(OperaFiscalUdfMapping mapping) {
        if (mapping == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (mapping.getTenantId() == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (mapping.getUdfName() == null || mapping.getUdfName().isBlank()) {
            throw new IllegalArgumentException("udfName is required");
        }
        mapping.setUdfName(normalizeUpper(mapping.getUdfName()));
        mapping.setUdfValue(normalizeNullable(mapping.getUdfValue()));
        if (mapping.getSortOrder() == null) {
            mapping.setSortOrder(100);
        }
        if (mapping.getActive() == null) {
            mapping.setActive(Boolean.TRUE);
        }
        return udfRepo.save(mapping);
    }

    public void deleteChargeMapping(Long id) {
        chargeRepo.deleteById(id);
    }

    public void deletePaymentMapping(Long id) {
        paymentRepo.deleteById(id);
    }

    public void deleteTaxMapping(Long id) {
        taxRepo.deleteById(id);
    }

    public void deleteUdfMapping(Long id) {
        udfRepo.deleteById(id);
    }

    private String normalizeUpper(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeUpperDefault(String value, String defaultValue) {
        String source = value == null || value.isBlank() ? defaultValue : value;
        return normalizeUpper(source);
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeNullableUpper(String value) {
        String normalized = normalizeNullable(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }
}
