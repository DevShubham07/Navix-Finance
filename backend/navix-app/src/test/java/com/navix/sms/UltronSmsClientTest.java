package com.navix.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Per-NotificationType DLT Template ID resolution (falls back to the global/OTP id). */
class UltronSmsClientTest {

    private static UltronSmsClient client(String globalId, Map<String, String> perType) {
        SmsProperties props = new SmsProperties(
                "https://ultronsms.test/api/mt/", "user", "password", null, "DhanBoost", "Trans",
                "route", "peid", globalId, perType,
                "otp {otp} {ttl}", true, false, 300, 6, false, "123456");
        return new UltronSmsClient(props);
    }

    @Test
    void resolvesPerTypeIdWhenMapped() {
        UltronSmsClient c = client("GLOBAL", Map.of("LOAN_DISBURSED", "DLT_LD_1"));
        assertThat(c.resolveDltTemplateId("LOAN_DISBURSED")).isEqualTo("DLT_LD_1");
    }

    @Test
    void fallsBackToGlobalForNullKey() {
        // OTP path passes a null key → uses the global/OTP id.
        UltronSmsClient c = client("GLOBAL", Map.of("LOAN_DISBURSED", "DLT_LD_1"));
        assertThat(c.resolveDltTemplateId(null)).isEqualTo("GLOBAL");
    }

    @Test
    void fallsBackToGlobalForUnmappedOrBlank() {
        UltronSmsClient c = client("GLOBAL", Map.of("LOAN_DISBURSED", "", "KYC_APPROVED", "DLT_KA"));
        assertThat(c.resolveDltTemplateId("REPAYMENT_VERIFIED")).isEqualTo("GLOBAL"); // unmapped
        assertThat(c.resolveDltTemplateId("LOAN_DISBURSED")).isEqualTo("GLOBAL");     // blank → fallback
    }

    @Test
    void fallsBackWhenMapIsNull() {
        UltronSmsClient c = client("GLOBAL", null);
        assertThat(c.resolveDltTemplateId("LOAN_DISBURSED")).isEqualTo("GLOBAL");
    }

    @Test
    void sharedDeclinedIdMapsBothTypes() {
        UltronSmsClient c = client("GLOBAL",
                Map.of("CREDIT_REJECTED", "DLT_DECLINED", "REBORROW_REVIEW_REJECTED", "DLT_DECLINED"));
        assertThat(c.resolveDltTemplateId("CREDIT_REJECTED")).isEqualTo("DLT_DECLINED");
        assertThat(c.resolveDltTemplateId("REBORROW_REVIEW_REJECTED")).isEqualTo("DLT_DECLINED");
    }
}
