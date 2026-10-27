package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.CatalogDtos;
import com.stackwizard.booking_api.dto.CatalogItemsInquiryRequest;
import com.stackwizard.booking_api.model.SalesCatalog;
import com.stackwizard.booking_api.security.ApiClientAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** Web (CMS) API of sales catalogs. Only m2m channel clients (ROLE_API, token_use=m2m) with X-Tenant-Id. */
@Service
public class CatalogWebService {
    private final CatalogViewService viewService;
    private final CatalogQuoteService quoteService;
    private final CatalogItemsInquiryService inquiryService;

    public CatalogWebService(CatalogViewService viewService, CatalogQuoteService quoteService,
                             CatalogItemsInquiryService inquiryService) {
        this.viewService = viewService;
        this.quoteService = quoteService;
        this.inquiryService = inquiryService;
    }

    @Transactional(readOnly = true)
    public List<CatalogDtos.CatalogSummary> catalogs() {
        ApiClientAccess.requireApiClient();
        return viewService.activeCatalogs().stream()
                .map(c -> new CatalogDtos.CatalogSummary(c.getCode(), c.getName(), c.getSaleMode()))
                .toList();
    }

    @Transactional(readOnly = true)
    public CatalogDtos.CatalogDetail catalog(String code) {
        ApiClientAccess.requireApiClient();
        SalesCatalog c = viewService.requireActiveByCode(code);
        return new CatalogDtos.CatalogDetail(c.getCode(), c.getName(), c.getSaleMode(), c.getCurrency());
    }

    @Transactional(readOnly = true)
    public List<CatalogDtos.SeasonView> seasons(String code, LocalDate from, LocalDate to) {
        ApiClientAccess.requireApiClient();
        return viewService.seasons(viewService.requireActiveByCode(code), from, to);
    }

    @Transactional(readOnly = true)
    public List<CatalogDtos.ItemView> items(String code, LocalDate date, LocalDate dateTo, Integer pax, String group) {
        ApiClientAccess.requireApiClient();
        return viewService.items(viewService.requireActiveByCode(code), date, dateTo, pax, CatalogViewService.parseGroups(group));
    }

    @Transactional(readOnly = true)
    public List<CatalogDtos.MapView> maps(String code, LocalDate date) {
        ApiClientAccess.requireApiClient();
        return viewService.maps(viewService.requireActiveByCode(code), date);
    }

    @Transactional(readOnly = true)
    public CatalogDtos.QuoteResponse quote(String code, CatalogDtos.QuoteRequest request) {
        ApiClientAccess.requireApiClient();
        return quoteService.quote(viewService.requireActiveByCode(code), request);
    }

    @Transactional
    public CatalogItemsInquiryService.Result inquiry(String code, CatalogItemsInquiryRequest request) {
        ApiClientAccess.requireApiClient();
        return inquiryService.submit(viewService.requireActiveByCode(code), request);
    }
}
