package com.navix.verification.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The PROVIDER_CALL log keeps a payload's shape and drops the borrower's identifiers. */
class ProviderLogRedactorTest {

    @Test
    void masksIdentifyingKeys_keepsEverythingNeededToDiagnose() {
        String out = ProviderLogRedactor.redact("""
                {"client_ref_num":"navix-42-BUREAU","pan":"ABCPE1234Z","first_name":"Jane","mobile_no":"9876543210",
                 "date_of_birth":"1990-01-01","otp":"654321","email":"jane@example.com","maskedName":"false",
                 "result_code":102,"http_response_code":200,"device_ip":"3.109.169.131",
                 "address":{"line1":"12 MG Road","pincode":"560001"},
                 "accounts":[{"Account_Number":"1234567890123","Subscriber_Name":"TEST BANK"}]}
                """);

        assertThat(out)
                .contains("\"client_ref_num\":\"navix-42-BUREAU\"", "\"result_code\":102",
                        "\"http_response_code\":200", "\"device_ip\":\"3.109.169.131\"", "\"maskedName\":\"false\"",
                        "\"pan\":\"[REDACTED]\"", "\"first_name\":\"[REDACTED]\"", "\"otp\":\"[REDACTED]\"",
                        "\"Account_Number\":\"[REDACTED]\"")
                .doesNotContain("ABCPE1234Z", "Jane", "9876543210", "1990-01-01", "654321", "jane@example.com",
                        "12 MG Road", "560001", "1234567890123", "TEST BANK");
    }

    @Test
    void scrubsIdentifiersEchoedInsideFreeText() {
        String out = ProviderLogRedactor.redact(
                "{\"message\":\"PAN ABCPE1234Z and mobile 9876543210 rejected\",\"status\":\"FAILED\"}");

        assertThat(out).isEqualTo(
                "{\"message\":\"PAN [REDACTED] and mobile [REDACTED] rejected\",\"status\":\"FAILED\"}");
    }

    @Test
    void nonJsonPayloadsGetTheFreeTextPass() {
        assertThat(ProviderLogRedactor.redact("upstream said PAN ABCPE1234Z unknown"))
                .isEqualTo("upstream said PAN [REDACTED] unknown");
        assertThat(ProviderLogRedactor.redact(null)).isNull();
    }
}
