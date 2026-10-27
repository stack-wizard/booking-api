package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.CrmLead;
import com.stackwizard.booking_api.service.CatalogInquiryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.server.ResponseStatusException;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CatalogInquiryControllerTest {

    private static final String VALID = """
            {
              "companyName": "Acme d.o.o.",
              "vatId": "HR12345678901",
              "firstName": "Ana",
              "lastName": "Horvat",
              "email": "ana@acme.hr",
              "phone": "+385 1 2345 678",
              "country": "HR",
              "eventType": "CONFERENCE",
              "dateFrom": "2026-11-10",
              "dateTo": "2026-11-11",
              "attendees": 120,
              "dayFormat": "FULL_DAY",
              "spaces": [{"resourceId": 11, "name": "Grand Hall", "setupStyle": "THEATRE"}],
              "services": [{"productId": null, "name": "Coffee break", "category": "CATERING",
                            "quantity": 240, "unit": "pax", "unitPrice": 6.5, "total": 1560}],
              "estimatedTotal": 4560,
              "currency": "EUR",
              "notes": "Need a stage",
              "sourceUrl": "https://example.com/mice",
              "cmsInquiryId": "cms-42"
            }
            """;

    @Mock
    private CatalogInquiryService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new CatalogInquiryController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void newInquiryReturns201WithLead() throws Exception {
        when(service.submit(any())).thenReturn(new CatalogInquiryService.Result(
                CrmLead.builder().id(99L).status(CrmLead.Status.NEW).build(), true));

        mvc.perform(post("/api/catalog/inquiries").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.leadId").value(99))
                .andExpect(jsonPath("$.status").value("NEW"));
    }

    @Test
    void replayReturns200WithExistingLead() throws Exception {
        when(service.submit(any())).thenReturn(new CatalogInquiryService.Result(
                CrmLead.builder().id(55L).status(CrmLead.Status.WORKING).build(), false));

        mvc.perform(post("/api/catalog/inquiries").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leadId").value(55))
                .andExpect(jsonPath("$.status").value("WORKING"));
    }

    @Test
    void userJwtRejectedByServiceIsForbidden() throws Exception {
        when(service.submit(any())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Only m2m channel clients may call this endpoint"));

        mvc.perform(post("/api/catalog/inquiries").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingRequiredFieldsReturn400() throws Exception {
        mvc.perform(post("/api/catalog/inquiries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyName\":\"Acme\",\"email\":\"not-an-email\",\"attendees\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("firstName")))
                .andExpect(jsonPath("$.message", containsString("email")))
                .andExpect(jsonPath("$.message", containsString("attendees")))
                .andExpect(jsonPath("$.message", containsString("eventType")));
        verifyNoInteractions(service);
    }

    @Test
    void dateToBeforeDateFromReturns400() throws Exception {
        mvc.perform(post("/api/catalog/inquiries").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace("\"dateTo\": \"2026-11-11\"", "\"dateTo\": \"2026-11-09\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("dateTo must not be before dateFrom")));
        verifyNoInteractions(service);
    }

    @Test
    void unknownFieldReturns400() throws Exception {
        mvc.perform(post("/api/catalog/inquiries").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace("\"notes\":", "\"ownerUserId\": 1, \"notes\":")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("unknown field")));
        verifyNoInteractions(service);
    }

    @Test
    void oversizedNotesReturn400() throws Exception {
        mvc.perform(post("/api/catalog/inquiries").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace("Need a stage", "x".repeat(4001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("notes")));
        verifyNoInteractions(service);
    }

    @Test
    void invalidEnumReturns400() throws Exception {
        mvc.perform(post("/api/catalog/inquiries").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace("\"CONFERENCE\"", "\"WEDDING\"")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
