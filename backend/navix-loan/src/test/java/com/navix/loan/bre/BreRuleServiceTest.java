package com.navix.loan.bre;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.featureflag.FeatureFlag;
import com.navix.common.featureflag.FeatureFlagRepository;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BreRuleServiceTest {

    @Mock private FeatureFlagService featureFlags;
    @Mock private FeatureFlagRepository flagRepository;
    @Mock private BreThresholdRepository thresholdRepository;
    @InjectMocks private BreRuleService service;

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    private static void as(String role) {
        ActorContext.set(new CurrentActor("1", "Tester", role));
    }

    private static String code(Runnable r) {
        try {
            r.run();
        } catch (BusinessException e) {
            return e.getCode();
        }
        return null;
    }

    @Test
    void nonAdminCannotWrite() {
        as("CREDIT_HEAD");
        assertThat(code(() -> service.setEnabled(BreRuleCatalog.DOB_MISMATCH, false))).isEqualTo("FORBIDDEN_ROLE");
        assertThat(code(() -> service.setThreshold(BreRuleCatalog.T_FRAUD_BLOCK_DAYS, 10))).isEqualTo("FORBIDDEN_ROLE");
    }

    @Test
    void dsaAndBorrowerCannotList() {
        as("DSA");
        assertThat(code(service::list)).isEqualTo("FORBIDDEN_ROLE");
        as("BORROWER");
        assertThat(code(service::list)).isEqualTo("FORBIDDEN_ROLE");
    }

    @Test
    void staffListsEveryRuleWithDefaults() {
        as("CREDIT_EXECUTIVE");
        lenient().when(flagRepository.findByFlagKey(any())).thenReturn(Optional.empty());
        lenient().when(thresholdRepository.findByThresholdKey(any())).thenReturn(Optional.empty());
        var rules = service.list();
        assertThat(rules).hasSize(BreRuleCatalog.RULES.size());
        var bureau = rules.stream().filter(r -> r.key().equals(BreRuleCatalog.LOW_BUREAU_SCORE)).findFirst().orElseThrow();
        assertThat(bureau.enabled()).isFalse();
        assertThat(bureau.thresholds()).extracting(t -> t.value()).containsExactly(550L, 90L);
        var review = rules.stream().filter(r -> r.group().equals("REVIEW")).findFirst().orElseThrow();
        assertThat(review.enabled()).isNull();
    }

    @Test
    void nonToggleableAndUnknownRefused() {
        as("ADMIN");
        assertThat(code(() -> service.setEnabled("always-blocklist", false))).isEqualTo("BRE_RULE_NOT_TOGGLEABLE");
        assertThat(code(() -> service.setEnabled("onboarding-paused", true))).isEqualTo("BRE_RULE_NOT_TOGGLEABLE");
        assertThat(code(() -> service.setEnabled("nope", true))).isEqualTo("BRE_RULE_NOT_FOUND");
        assertThat(code(() -> service.setThreshold("nope", 5))).isEqualTo("BRE_THRESHOLD_NOT_FOUND");
    }

    @Test
    void outOfRangeRefused() {
        as("ADMIN");
        assertThat(code(() -> service.setThreshold(BreRuleCatalog.T_BUREAU_FLOOR, 299))).isEqualTo("BRE_THRESHOLD_OUT_OF_RANGE");
        assertThat(code(() -> service.setThreshold(BreRuleCatalog.T_BUREAU_FLOOR, 901))).isEqualTo("BRE_THRESHOLD_OUT_OF_RANGE");
        assertThat(code(() -> service.setThreshold(BreRuleCatalog.T_FRAUD_BLOCK_DAYS, 0))).isEqualTo("BRE_THRESHOLD_OUT_OF_RANGE");
        assertThat(code(() -> service.setThreshold(BreRuleCatalog.T_FRAUD_BLOCK_DAYS, 1.5))).isEqualTo("BRE_THRESHOLD_OUT_OF_RANGE");
        assertThat(code(() -> service.setThreshold(BreRuleCatalog.T_FRAUD_BLOCK_DAYS, null))).isEqualTo("BRE_THRESHOLD_OUT_OF_RANGE");
    }

    @Test
    void adminSavesThresholdAndFlag() {
        as("ADMIN");
        when(thresholdRepository.findByThresholdKey(BreRuleCatalog.T_BUREAU_FLOOR)).thenReturn(Optional.empty());
        var view = service.setThreshold(BreRuleCatalog.T_BUREAU_FLOOR, 600);
        verify(thresholdRepository).save(any(BreThreshold.class));
        assertThat(view.defaultValue()).isEqualTo(550L);

        when(flagRepository.findByFlagKey("bre-dob-mismatch-reject")).thenReturn(Optional.empty());
        service.setEnabled(BreRuleCatalog.DOB_MISMATCH, false);
        verify(flagRepository).save(any(FeatureFlag.class));
    }

    @Test
    void missingRowFallsBackToDefault() {
        when(thresholdRepository.findByThresholdKey(BreRuleCatalog.T_BUREAU_FLOOR)).thenReturn(Optional.empty());
        assertThat(service.threshold(BreRuleCatalog.T_BUREAU_FLOOR)).isEqualTo(550);
        BreThreshold row = new BreThreshold();
        row.setValue(620);
        when(thresholdRepository.findByThresholdKey(BreRuleCatalog.T_BUREAU_FLOOR)).thenReturn(Optional.of(row));
        assertThat(service.threshold(BreRuleCatalog.T_BUREAU_FLOOR)).isEqualTo(620);
        when(featureFlags.isEnabled("bre-dob-mismatch-reject", true)).thenReturn(true);
        assertThat(service.isOn(BreRuleCatalog.DOB_MISMATCH)).isTrue();
    }
}
