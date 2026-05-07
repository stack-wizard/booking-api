package com.stackwizard.booking_api.service.fiscal;

import com.stackwizard.booking_api.model.OperaFiscalChargeMapping;
import com.stackwizard.booking_api.model.OperaFiscalPaymentMapping;
import com.stackwizard.booking_api.model.OperaFiscalTaxMapping;
import com.stackwizard.booking_api.model.OperaFiscalUdfMapping;
import com.stackwizard.booking_api.repository.OperaFiscalChargeMappingRepository;
import com.stackwizard.booking_api.repository.OperaFiscalPaymentMappingRepository;
import com.stackwizard.booking_api.repository.OperaFiscalTaxMappingRepository;
import com.stackwizard.booking_api.repository.OperaFiscalUdfMappingRepository;
import com.stackwizard.booking_api.service.PaymentCardTypeService;
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
    private final PaymentCardTypeService paymentCardTypeService;

    public OperaFiscalMappingService(OperaFiscalChargeMappingRepository chargeRepo,
                                     OperaFiscalPaymentMappingRepository paymentRepo,
                                     OperaFiscalTaxMappingRepository taxRepo,
                                     OperaFiscalUdfMappingRepository udfRepo,
                                     PaymentCardTypeService paymentCardTypeService) {
        this.chargeRepo = chargeRepo;
        this.paymentRepo = paymentRepo;
        this.taxRepo = taxRepo;
        this.udfRepo = udfRepo;
        this.paymentCardTypeService = paymentCardTypeService;
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
        return resolvePaymentMapping(tenantId, paymentType, null);
    }

    public Optional<OperaFiscalPaymentMapping> resolvePaymentMapping(Long tenantId, String paymentType, String cardType) {
        if (tenantId == null || paymentType == null || paymentType.isBlank()) {
            return Optional.empty();
        }
        String normalizedPaymentType = paymentType.trim();
        if (cardType != null && !cardType.isBlank()) {
            String configuredCardType = paymentCardTypeService.findActiveCodeOrNull(tenantId, cardType.trim());
            if (configuredCardType != null) {
                Optional<OperaFiscalPaymentMapping> byCardType = paymentRepo
                        .findFirstByTenantIdAndActiveTrueAndPaymentTypeIgnoreCaseAndCardTypeIgnoreCase(
                                tenantId,
                                normalizedPaymentType,
                                configuredCardType
                        );
                if (byCardType.isPresent()) {
                    return byCardType;
                }
            }
        }
        return paymentRepo.findFirstByTenantIdAndActiveTrueAndPaymentTypeIgnoreCaseAndCardTypeIsNull(
                tenantId,
                normalizedPaymentType
        );
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
        String trxType = normalizeUpperDefault(mapping.getTrxType(), "C");
        if (!"C".equals(trxType) && !"FC".equals(trxType)) {
            throw new IllegalArgumentException(
                    "trxType for charge mappings must be 'C' or 'FC' (OHIP transaction category), not '"
                            + trxType + "'. Put the OHIP posting code in trxCode only; trxCodeType is separate "
                            + "(often 'L').");
        }
        mapping.setTrxType(trxType);
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
        mapping.setCardType(normalizeNullableUpper(mapping.getCardType()));
        if (mapping.getCardType() != null && !"CARD".equals(mapping.getPaymentType())) {
            throw new IllegalArgumentException("cardType is supported only for paymentType CARD");
        }
        if (mapping.getCardType() != null) {
            mapping.setCardType(paymentCardTypeService.requireActiveCode(mapping.getTenantId(), mapping.getCardType()));
        }
        mapping.setTrxCode(normalizeUpper(mapping.getTrxCode()));
        mapping.setPaymentMethodCode(normalizeNullableUpper(mapping.getPaymentMethodCode()));
        String trxType = normalizeUpperDefault(mapping.getTrxType(), "FC");
        if (!"C".equals(trxType) && !"FC".equals(trxType)) {
            throw new IllegalArgumentException(
                    "trxType for payment mappings must be 'C' or 'FC', not '" + trxType
                            + "'. Put the OHIP transaction code in trxCode.");
        }
        mapping.setTrxType(trxType);
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
