package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.CatalogDtos;
import com.stackwizard.booking_api.model.CrmLead;
import com.stackwizard.booking_api.model.SalesCatalog;
import com.stackwizard.booking_api.service.CatalogEventTypeService;
import com.stackwizard.booking_api.service.CatalogItemsInquiryService;
import com.stackwizard.booking_api.service.CatalogQuoteService;
import com.stackwizard.booking_api.service.CatalogViewService;
import com.stackwizard.booking_api.service.CatalogWebService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every /api/catalogs endpoint: m2m only (user JWT 403, anonymous 401), plus request validation. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CatalogWebControllerTest {
    private static final String INQUIRY = """
            {"companyName":"Acme","firstName":"Ana","lastName":"Horvat","email":"ana@acme.hr","eventType":"CONFERENCE",
             "dateFrom":"2027-05-13","dateTo":"2027-05-14","attendees":60,"items":[{"itemId":41}],"cmsInquiryId":"cms-1"}
            """;
    private static final String QUOTE = """
            {"dateFrom":"2027-05-13","dateTo":"2027-05-14","attendees":60,"dayFormat":"FULL_DAY",
             "items":[{"itemId":41},{"itemId":57,"quantity":120}]}
            """;

    @Mock CatalogViewService viewService;
    @Mock CatalogQuoteService quoteService;
    @Mock CatalogItemsInquiryService inquiryService;
    @Mock CatalogEventTypeService eventTypeService;

    MockMvc mvc;
    SalesCatalog mice = SalesCatalog.builder().id(1L).code("MICE").name("Meetings").saleMode(SalesCatalog.SaleMode.INQUIRY)
            .currency("EUR").build();

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new CatalogWebController(new CatalogWebService(viewService, quoteService, inquiryService, eventTypeService)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
        when(viewService.activeCatalogs()).thenReturn(List.of(mice));
        when(viewService.requireActiveByCode(anyString())).thenReturn(mice);
        when(viewService.items(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(viewService.seasons(any(), any(), any())).thenReturn(List.of());
        when(viewService.maps(any(), any())).thenReturn(List.of());
        when(quoteService.quote(any(), any())).thenReturn(new CatalogDtos.QuoteResponse("EUR", List.of(),
                new BigDecimal("0.00"), new BigDecimal("0.00"), false));
        when(inquiryService.submit(any(), any())).thenReturn(new CatalogItemsInquiryService.Result(
                CrmLead.builder().id(99L).status(CrmLead.Status.NEW).build(), true, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static List<MockHttpServletRequestBuilder> endpoints() {
        return List.of(
                get("/api/catalogs"),
                get("/api/catalogs/MICE"),
                get("/api/catalogs/MICE/seasons"),
                get("/api/catalogs/MICE/items").param("group", "HALL,CATERING"),
                get("/api/catalogs/MICE/maps").param("date", "2027-05-13"),
                post("/api/catalogs/MICE/quote").contentType(MediaType.APPLICATION_JSON).content(QUOTE),
                post("/api/catalogs/MICE/inquiries").contentType(MediaType.APPLICATION_JSON).content(INQUIRY));
    }

    private static void authenticate(Map<String, Object> claims, String authority) {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(3600), Map.of("alg", "none"), claims);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(authority))));
    }

    @Test
    void m2mClientIsAllowedEverywhere() throws Exception {
        authenticate(Map.of("token_use", "m2m", "scope", "booking.api"), "ROLE_API");
        for (MockHttpServletRequestBuilder endpoint : endpoints()) {
            mvc.perform(endpoint).andExpect(status().is2xxSuccessful());
        }
    }

    @Test
    void userJwtIsForbiddenEverywhere() throws Exception {
        authenticate(Map.of("sub", "21111111-1111-1111-1111-111111111111"), "ROLE_ADMIN");
        for (MockHttpServletRequestBuilder endpoint : endpoints()) {
            mvc.perform(endpoint).andExpect(status().isForbidden());
        }
        verifyNoInteractions(quoteService, inquiryService);
    }

    @Test
    void roleApiWithoutM2mTokenIsForbidden() throws Exception {
        authenticate(Map.of("sub", "x"), "ROLE_API");
        mvc.perform(get("/api/catalogs")).andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsUnauthorizedEverywhere() throws Exception {
        for (MockHttpServletRequestBuilder endpoint : endpoints()) {
            mvc.perform(endpoint).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void catalogListAndDetailShapes() throws Exception {
        authenticate(Map.of("token_use", "m2m"), "ROLE_API");
        mvc.perform(get("/api/catalogs")).andExpect(jsonPath("$[0].code").value("MICE"))
                .andExpect(jsonPath("$[0].saleMode").value("INQUIRY"));
        mvc.perform(get("/api/catalogs/MICE")).andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    void newInquiry201AndReplay200() throws Exception {
        authenticate(Map.of("token_use", "m2m"), "ROLE_API");
        mvc.perform(post("/api/catalogs/MICE/inquiries").contentType(MediaType.APPLICATION_JSON).content(INQUIRY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.leadId").value(99));
        when(inquiryService.submit(any(), any())).thenReturn(new CatalogItemsInquiryService.Result(
                CrmLead.builder().id(99L).status(CrmLead.Status.NEW).build(), false, null));
        mvc.perform(post("/api/catalogs/MICE/inquiries").contentType(MediaType.APPLICATION_JSON).content(INQUIRY))
                .andExpect(status().isOk());
    }

    @Test
    void invalidBodiesReturn400WithFieldMessages() throws Exception {
        authenticate(Map.of("token_use", "m2m"), "ROLE_API");
        mvc.perform(post("/api/catalogs/MICE/inquiries").contentType(MediaType.APPLICATION_JSON)
                        .content(INQUIRY.replace("\"items\":[{\"itemId\":41}]", "\"items\":[],\"spaces\":[]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("items")))
                .andExpect(jsonPath("$.message", containsString("unknown field")));
        mvc.perform(post("/api/catalogs/MICE/quote").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateFrom\":\"2027-05-14\",\"dateTo\":\"2027-05-13\",\"items\":[{}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("dateTo must not be before dateFrom")))
                .andExpect(jsonPath("$.message", containsString("itemId")));
        mvc.perform(get("/api/catalogs/MICE/maps")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("date is required"));
        mvc.perform(get("/api/catalogs/MICE/items").param("date", "13.05.2027")).andExpect(status().isBadRequest());
    }
}
