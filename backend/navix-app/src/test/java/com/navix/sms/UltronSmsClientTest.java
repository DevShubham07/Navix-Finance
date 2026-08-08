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
                "otp {otp} {ttl}", null, true, false, 300, 6, false, "123456");
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

    /**
     * The DHANBOOST_*_V1 batch is Active on DLT, so application.yml ships every id as a default —
     * a blanked default silently drops the DLT tag and the gateway rejects the send with 006.
     */
    @Test
    void everyMappedTypeShipsARealDltIdDefault() throws Exception {
        String yml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/application.yml"));
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^      (\\w+): \\$\\{NAVIX_SMS_DLT_\\w+:([^}]*)\\}$", java.util.regex.Pattern.MULTILINE)
                .matcher(yml);
        int mapped = 0;
        while (m.find()) {
            assertThat(m.group(2)).as("DLT id default for %s", m.group(1)).matches("\\d{19}");
            mapped++;
        }
        assertThat(mapped).isEqualTo(15);
    }

    /**
     * Spaces in the body must go out as {@code %20}. UltronSMS reads a {@code +} literally, so a
     * {@code +}-encoded body fails its char-for-char template match with {@code 006 Invalid template
     * text} even when the text is correct. Pins the encoding the client's UriBuilder actually uses.
     */
    @Test
    void encodesSpacesAsPercent20NotPlus() {
        String uri = new org.springframework.web.util.DefaultUriBuilderFactory("https://ultronsms.test/api/mt/")
                .uriString("SendSMS")
                .queryParam("text", "Your loan with DhanBoost is fully repaid and closed. - DhanBoost")
                .build()
                .toString();
        assertThat(uri).contains("Your%20loan%20with%20DhanBoost").doesNotContain("+");
    }

    @Test
    void sharedDeclinedIdMapsBothTypes() {
        UltronSmsClient c = client("GLOBAL",
                Map.of("CREDIT_REJECTED", "DLT_DECLINED", "REBORROW_REVIEW_REJECTED", "DLT_DECLINED"));
        assertThat(c.resolveDltTemplateId("CREDIT_REJECTED")).isEqualTo("DLT_DECLINED");
        assertThat(c.resolveDltTemplateId("REBORROW_REVIEW_REJECTED")).isEqualTo("DLT_DECLINED");
    }
}
