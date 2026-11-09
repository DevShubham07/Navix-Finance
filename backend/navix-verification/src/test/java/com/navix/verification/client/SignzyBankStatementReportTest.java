package com.navix.verification.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The envelope we keep must not carry the statement PDF password Signzy echoes back. */
class SignzyBankStatementReportTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ANALYZE = """
            {"report":{"reportId":"PR1","reportStatus":"IN PROGRESS"},
             "reportAccounts":[{"account":{"accountNumber":"17330200002493","bankName":"Federal Bank"},
               "fileDetails":{"success":[{"fileId":1,"password":"Se\\"cret@1"}],"failed":[]}}]}
            """;

    private static final String WORK_ORDER = """
            {"report":{"reportId":"PR1","reportStatus":"ANALYSED"},
             "reportAccounts":[{"account":{"accountNumber":"17330200002493","bankName":"Federal Bank"},
               "files":[{"fileId":1,"password":"390401502910","txnCount":"12"}]}]}
            """;

    @Test
    void masksThePasswordInBothEnvelopesAndKeepsValidJson() throws Exception {
        for (String envelope : new String[] {ANALYZE, WORK_ORDER}) {
            String kept = SignzyBankStatementClient.report(JSON.readTree(envelope)).rawJson();

            assertThat(kept).doesNotContain("cret@1").doesNotContain("390401502910");
            JsonNode parsed = JSON.readTree(kept);
            assertThat(parsed.findValue("password").asText()).isEqualTo("[REDACTED]");
            // Everything the console reads back out of the stored row survives.
            assertThat(SignzyBankStatementClient.accountsOf(parsed).get(0).bankName()).isEqualTo("Federal Bank");
        }
    }
}
