package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.CrmPipelineStage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrmStageRulesTest {

    private static CrmPipelineStage stage(long id, int order, CrmPipelineStage.StageKind kind,
                                          boolean back, boolean skip, boolean locks) {
        return CrmPipelineStage.builder().id(id).name("S" + id).displayOrder(order).stageKind(kind)
                .allowBack(back).allowSkip(skip).locksIntake(locks).build();
    }

    final CrmPipelineStage inquiry = stage(1, 10, CrmPipelineStage.StageKind.OPEN, true, false, false);
    final CrmPipelineStage qualified = stage(2, 20, CrmPipelineStage.StageKind.OPEN, false, true, true);
    final CrmPipelineStage proposal = stage(3, 30, CrmPipelineStage.StageKind.OPEN, true, true, false);
    final CrmPipelineStage won = stage(8, 80, CrmPipelineStage.StageKind.WON, true, true, false);
    final CrmPipelineStage lost = stage(9, 90, CrmPipelineStage.StageKind.LOST, true, true, false);
    final List<CrmPipelineStage> stages = List.of(inquiry, qualified, proposal, won, lost);

    @Test
    void stageWithoutSkipMovesOnlyToTheNextOpenStage() {
        assertThatCode(() -> CrmOpportunityService.checkTransition(inquiry, qualified, stages)).doesNotThrowAnyException();
        assertThatThrownBy(() -> CrmOpportunityService.checkTransition(inquiry, proposal, stages))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot skip");
    }

    @Test
    void stageWithoutBackCannotReturn() {
        assertThatThrownBy(() -> CrmOpportunityService.checkTransition(qualified, inquiry, stages))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot move back");
        assertThatCode(() -> CrmOpportunityService.checkTransition(proposal, qualified, stages)).doesNotThrowAnyException();
    }

    @Test
    void closingAndReopeningIgnoreTheFlags() {
        assertThatCode(() -> CrmOpportunityService.checkTransition(inquiry, won, stages)).doesNotThrowAnyException();
        assertThatCode(() -> CrmOpportunityService.checkTransition(qualified, lost, stages)).doesNotThrowAnyException();
        assertThatCode(() -> CrmOpportunityService.checkTransition(lost, inquiry, stages)).doesNotThrowAnyException();
    }

    @Test
    void lockingStageAndEveryLaterOpenStageLockTheIntake() {
        assertThat(CrmOpportunityService.locksIntake(inquiry, stages)).isFalse();
        assertThat(CrmOpportunityService.locksIntake(qualified, stages)).isTrue();
        assertThat(CrmOpportunityService.locksIntake(proposal, stages)).isTrue();
        assertThat(CrmOpportunityService.locksIntake(lost, stages)).isFalse();
    }

    @Test
    void onlyChangedIntakeKeysAreReported() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode before = mapper.createObjectNode().put("pax", 120).put("eventType", "CONFERENCE").put("title", "A");
        before.putArray("catering");
        ObjectNode after = mapper.createObjectNode().put("pax", 120.0).put("eventType", "CONFERENCE").put("title", "B")
                .put("dietary", "");

        assertThat(CrmOpportunityService.changedIntakeKeys(before, after)).isEmpty();

        after.put("pax", 150);
        assertThat(CrmOpportunityService.changedIntakeKeys(before, after)).containsExactly("pax");
    }
}
