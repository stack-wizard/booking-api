package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwizard.booking_api.dto.OperaDtos;
import com.stackwizard.booking_api.model.OperaCode;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.repository.OperaCodeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperaCodeImportServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private OperaCodeImportService.Source source(OperaCode.Kind kind) {
        return OperaCodeImportService.SOURCES.stream().filter(s -> s.kind() == kind).findFirst().orElseThrow();
    }

    @Test
    void blockStatusesKeepTypesAndConfiguredFlow() throws Exception {
        JsonNode response = mapper.readTree("""
                {"blockStatusCodeMasterList":[
                  {"status":{"code":"INQ","description":"Inquiry"},"roomStatusType":"Inquiry","cateringStatusType":"Inquiry",
                   "color":"Blue","starting":true,"orderBy":1},
                  {"status":{"code":"TEN","description":"Tentative"},"cateringStatusType":"NonDeductInv","orderBy":2}],
                 "nextBlockStatusCodeList":[
                  {"status":"INQ","nextConfiguredStatusList":[{"status":"LOS","order":2},{"status":"TEN","order":1}]}]}
                """);

        List<OperaCodeImportService.Row> rows = OperaCodeImportService.parse(source(OperaCode.Kind.BLOCK_STATUS), response);

        assertThat(rows).extracting(OperaCodeImportService.Row::code).containsExactly("INQ", "TEN");
        JsonNode inquiry = rows.get(0).attrs();
        assertThat(rows.get(0).description()).isEqualTo("Inquiry");
        assertThat(inquiry.path("cateringStatusType").asText()).isEqualTo("Inquiry");
        assertThat(inquiry.path("starting").asBoolean()).isTrue();
        assertThat(inquiry.path("orderBy").asInt()).isEqualTo(1);
        assertThat(inquiry.path("next")).extracting(JsonNode::asText).containsExactly("TEN", "LOS");
        assertThat(rows.get(1).attrs().has("next")).isFalse();
    }

    @Test
    void nestedListsAndTranslatableTextsAreRead() throws Exception {
        JsonNode guarantees = mapper.readTree("""
                {"guaranteeCodes":[{"guaranteeCode":[
                  {"guaranteeCode":"GTD","description":"Guaranteed","reserveInventory":true},
                  {"guaranteeCode":"6PM","description":"6 PM hold","reserveInventory":true,"inactive":true}]}]}
                """);
        List<OperaCodeImportService.Row> rows = OperaCodeImportService.parse(source(OperaCode.Kind.GUARANTEE), guarantees);
        assertThat(rows).extracting(OperaCodeImportService.Row::code).containsExactly("GTD", "6PM");
        assertThat(rows).extracting(OperaCodeImportService.Row::active).containsExactly(true, false);

        JsonNode spaces = mapper.readTree("""
                {"functionSpaces":[
                  {"roomCode":"BALL","spaceName":{"defaultText":"Ballroom"},"spaceType":"BALLROOM"},
                  {"roomCode":"SAL1","spaceName":{"translatedTexts":[{"value":"Salon 1","language":"E"}]}},
                  {"spaceName":{"defaultText":"No code"}}]}
                """);
        rows = OperaCodeImportService.parse(source(OperaCode.Kind.FUNCTION_SPACE), spaces);
        assertThat(rows).extracting(OperaCodeImportService.Row::description).containsExactly("Ballroom", "Salon 1");
        assertThat(rows.get(0).attrs().path("spaceType").asText()).isEqualTo("BALLROOM");

        JsonNode rates = mapper.readTree("""
                {"ratePlanShortInfoList":{"ratePlanShortInfo":[
                  {"ratePlanCode":"MICE","primaryDetails":{"description":"MICE group"},
                   "classifications":{"rateCategory":"GRP","marketCode":"CONF"},"discountAllowed":false}]}}
                """);
        rows = OperaCodeImportService.parse(source(OperaCode.Kind.RATE_CODE), rates);
        assertThat(rows.get(0).description()).isEqualTo("MICE group");
        assertThat(rows.get(0).attrs().path("rateCategory").asText()).isEqualTo("GRP");
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsEveryPageOfAPagedKind() throws Exception {
        OperaPostingClient client = mock(OperaPostingClient.class);
        OperaTenantConfigResolver resolver = mock(OperaTenantConfigResolver.class);
        OperaCodeRepository repo = mock(OperaCodeRepository.class);
        when(repo.findByTenantIdAndOperaHotelIdAndKindOrderByCodeAsc(any(), any(), any())).thenReturn(List.of());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        JsonNode empty = mapper.readTree("{}");
        JsonNode first = mapper.readTree("""
                {"ratePlanShortInfoList":{"hasMore":true,"ratePlanShortInfo":[{"ratePlanCode":"A"},{"ratePlanCode":"B"}]}}
                """);
        JsonNode second = mapper.readTree("""
                {"ratePlanShortInfoList":{"hasMore":false,"ratePlanShortInfo":[{"ratePlanCode":"C"}]}}
                """);
        when(client.get(any(), any(), anyString(), anyString(), any())).thenAnswer(inv -> {
            if (!"/rtp/v1/ratePlans".equals(inv.getArgument(3))) {
                return empty;
            }
            Map<String, List<String>> query = inv.getArgument(4);
            return query.get("offset").equals(List.of("0")) ? first : second;
        });
        OperaCodeImportService service = new OperaCodeImportService(client, resolver, repo, mock(PlatformTransactionManager.class));

        List<OperaDtos.ImportKindResult> results = service.importCodes(
                OperaHotel.builder().id(1L).tenantId(1L).hotelCode("DH").build());

        OperaDtos.ImportKindResult rates = results.stream().filter(r -> r.getKind() == OperaCode.Kind.RATE_CODE).findFirst().orElseThrow();
        assertThat(rates.getError()).isNull();
        assertThat(rates.getImported()).isEqualTo(3);
    }
}
