package com.navix.verification.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The PROVIDER_CALL log keeps a payload's shape and drops the borrower's identifiers. */
class ProviderLogRedactorTest {

    /**
     * The bank-statement-analysis request, which carries three credentials at once: a presigned S3
     * URL whose query string IS the authorisation to a borrower's statement, the borrower's PDF
     * password (which never expires), and our own vendor authToken. None may reach CloudWatch.
     */
    private static final String BSA_REQUEST = """
            {"authToken":"eyJhbGciOiJIUzI1NiJ9.abc.def",
             "files":[{"fileUrl":"https://navix-finance-bucket.s3.ap-south-1.amazonaws.com/applications/9995/bank_statement/1789787768518.pdf?X-Amz-Signature=deadbeefcafe&X-Amz-Credential=AKIAEXAMPLE","password":"Statement@123"}],
             "customerName":"Jane Doe","statementPeriod":"6m"}
            """;

    @Test
    void masksCredentialsInTheLog_presignedUrlPasswordAndAuthToken() {
        String out = ProviderLogRedactor.redact(BSA_REQUEST);

        assertThat(out).doesNotContain("X-Amz-Signature").doesNotContain("deadbeefcafe");
        assertThat(out).doesNotContain("Statement@123").doesNotContain("eyJhbGciOiJIUzI1NiJ9");
        // The shape survives, so a failing integration is still diagnosable from the log line.
        assertThat(out).contains("fileUrl").contains("password").contains("authToken")
                .contains("\"statementPeriod\":\"6m\"");
    }

    @Test
    void storedAuditRowDropsCredentialsButKeepsIdentifiers() {
        String out = ProviderLogRedactor.redactCredentials(BSA_REQUEST);

        assertThat(out).doesNotContain("X-Amz-Signature").doesNotContain("Statement@123")
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9");
        // Unlike the log pass, the access-controlled row deliberately keeps the borrower data —
        // it is the record a failing integration is diagnosed from.
        assertThat(out).contains("Jane Doe").contains("6m");
    }

    @Test
    void redactCredentialsLeavesNonJsonAndEmptyPayloadsAlone() {
        assertThat(ProviderLogRedactor.redactCredentials("<html>502 Bad Gateway</html>"))
                .isEqualTo("<html>502 Bad Gateway</html>");
        assertThat(ProviderLogRedactor.redactCredentials(null)).isNull();
    }

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

    @Test
    void masksNumericIdentifiersUnderNeutralKeys_keepsOrdinaryNumbers() {
        String out = ProviderLogRedactor.redact(
                "{\"contact\":9876543210,\"ref\":[123456789012],\"score\":812,\"result_code\":101}");

        assertThat(out).isEqualTo(
                "{\"contact\":\"[REDACTED]\",\"ref\":[\"[REDACTED]\"],\"score\":812,\"result_code\":101}");
    }
}
