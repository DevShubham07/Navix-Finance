package com.navix.app.bsa;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.verification.client.SignzyBankStatementClient;
import com.navix.verification.dto.SignzyDtos.BsaAccount;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The parsing that stands between Signzy's envelope and the Banking tab. Both envelope shapes are
 * exercised because analyze-statement hangs the per-file details off {@code fileDetails.success[]}
 * while retrieve-work-order uses {@code files[]} — reading only one of them silently loses the
 * transaction count and the tampering verdict.
 */
class BankStatementAnalysisMappingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Shape of retrieve-work-order: two accounts, one of them with two files. */
    private static final String WORK_ORDER = """
            {"report":{"reportId":"PR48186","reportStatus":"ANALYSED",
                       "reportStatusDescription":"ANALYSED (2 ACCOUNTS) - CAM READY","bankScore":"912"},
             "reportAccounts":[
               {"account":{"accountNumber":"17330200002493","accountTypeName":"Current Account",
                           "bankName":"Federal Bank","status":"ANALYSED","ifscCode":"FDRL0001733",
                           "micrCode":"695049022","branchName":"BALARAMAPURAM",
                           "accountHolderName":"ZAMBROTTA RETAIL","startDate":"2023-08-03","endDate":"2024-01-30"},
                "files":[{"txnCount":"1403","authenticity":"ORIGINAL"},
                         {"txnCount":"97","authenticity":"FRAUD SUSPECTED"}]},
               {"account":{"accountNumber":"XXXXXXXX2910","accountTypeName":"Savings Account",
                           "bankName":"ICICI Bank","status":"ANALYSED"},
                "files":[{"txnCount":"11","authenticity":"ORIGINAL"}]}]}
            """;

    /** Shape of analyze-statement: no txnCount yet, details under fileDetails.success. */
    private static final String ANALYZE = """
            {"report":{"reportId":"PR22534","reportStatus":"IN PROGRESS","reportStatusDescription":"IN PROGRESS"},
             "reportAccounts":[
               {"account":{"accountNumber":"6012XXXXXXX4312","accountTypeName":"Savings Account",
                           "bankName":"State Bank of India","status":"INITIALISING"},
                "fileDetails":{"success":[{"fileId":473,"status":"INITIALISING"}],"failed":[]}}]}
            """;

    @Test
    void readsAccountsAndTheWorstAuthenticityFromAWorkOrder() throws Exception {
        List<BsaAccount> accounts = SignzyBankStatementClient.accountsOf(JSON.readTree(WORK_ORDER));

        assertThat(accounts).hasSize(2);
        BsaAccount first = accounts.get(0);
        assertThat(first.bankName()).isEqualTo("Federal Bank");
        assertThat(first.ifsc()).isEqualTo("FDRL0001733");
        assertThat(first.startDate()).isEqualTo("2023-08-03");
        // Both files count, and the worse of the two verdicts is the one staff must see.
        assertThat(first.txnCount()).isEqualTo(1500);
        assertThat(first.authenticity()).isEqualTo("FRAUD SUSPECTED");
        assertThat(accounts.get(1).txnCount()).isEqualTo(11);
    }

    @Test
    void readsTheAnalyzeShapeWhereOnlyFileDetailsExist() throws Exception {
        List<BsaAccount> accounts = SignzyBankStatementClient.accountsOf(JSON.readTree(ANALYZE));

        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).bankName()).isEqualTo("State Bank of India");
        assertThat(accounts.get(0).txnCount()).isNull();
        assertThat(accounts.get(0).authenticity()).isNull();
    }

    @Test
    void mapsSignzyReportStatusOntoOurs() {
        assertThat(BankStatementAnalysisService.statusOf("ANALYSED")).isEqualTo(BankStatementAnalysis.ANALYSED);
        assertThat(BankStatementAnalysisService.statusOf("ANALYSIS ON HOLD")).isEqualTo(BankStatementAnalysis.FAILED);
        assertThat(BankStatementAnalysisService.statusOf("IN PROGRESS")).isEqualTo(BankStatementAnalysis.PENDING);
        // An unknown state is still in flight — never silently "done".
        assertThat(BankStatementAnalysisService.statusOf("SOMETHING NEW")).isEqualTo(BankStatementAnalysis.PENDING);
        assertThat(BankStatementAnalysisService.statusOf(null)).isEqualTo(BankStatementAnalysis.PENDING);
    }

    @Test
    void masksAllButTheLastFourDigits() {
        assertThat(BankStatementAnalysisService.mask("17330200002493")).isEqualTo("XXXXXXXXXX2493");
        assertThat(BankStatementAnalysisService.mask("2493")).isEqualTo("2493");
        assertThat(BankStatementAnalysisService.mask(null)).isNull();
    }
}
