package com.navix.loan.payu;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.loan.payu.PayuClient.VerifyResult;
import org.junit.jupiter.api.Test;

/** Fixtures are real sandbox responses (docs/payu/README.md section 15). */
class PayuClientTest {

    private static final String CAPTURED = "{\"status\":1,\"msg\":\"1 out of 1 Transactions Fetched Successfully\","
            + "\"transaction_details\":{\"T1\":{\"mihpayid\":\"31543674597\",\"bank_ref_num\":\"451032969806011700\","
            + "\"amt\":\"102.36\",\"transaction_amount\":\"100.00\",\"txnid\":\"T1\",\"additional_charges\":\"2.36\","
            + "\"bankcode\":\"CC\",\"error_code\":\"E000\",\"addedon\":\"2026-11-09 02:05:41\","
            + "\"net_amount_debit\":102.36,\"mode\":\"CC\",\"status\":\"success\",\"unmappedstatus\":\"captured\"}}}";
    private static final String INITIATED = "{\"status\":1,\"msg\":\"1 out of 1 Transactions Fetched Successfully\","
            + "\"transaction_details\":{\"T2\":{\"mihpayid\":\"31543674523\",\"status\":\"pending\","
            + "\"unmappedstatus\":\"initiated\",\"mode\":\"-\",\"amt\":\"10.00\",\"transaction_amount\":\"10.00\","
            + "\"net_amount_debit\":\"0.00\",\"addedon\":\"2026-11-09 01:47:58\"}}}";
    private static final String NOT_FOUND = "{\"status\":0,\"msg\":\"0 out of 1 Transactions Fetched Successfully\","
            + "\"transaction_details\":{\"T3\":{\"mihpayid\":\"Not Found\",\"status\":\"Not Found\"}}}";

    @Test
    void captured_cardPaymentKeepsAmountAndFeeApart() {
        VerifyResult r = PayuClient.parse(CAPTURED, "T1").orElseThrow();
        assertThat(r.found()).isTrue();
        assertThat(r.status()).isEqualTo("success");
        assertThat(r.unmappedstatus()).isEqualTo("captured");
        assertThat(r.mihpayid()).isEqualTo("31543674597");
        assertThat(r.transactionAmount()).isEqualTo("100.00");
        assertThat(r.additionalCharges()).isEqualTo("2.36");
        assertThat(r.mode()).isEqualTo("CC");
        assertThat(r.bankcode()).isEqualTo("CC");
        assertThat(r.bankRefNum()).isEqualTo("451032969806011700");
        assertThat(r.addedon()).isEqualTo("2026-11-09 02:05:41");
        assertThat(r.errorCode()).isEqualTo("E000");
    }

    @Test
    void initiated_isFoundButPending() {
        VerifyResult r = PayuClient.parse(INITIATED, "T2").orElseThrow();
        assertThat(r.found()).isTrue();
        assertThat(r.status()).isEqualTo("pending");
        assertThat(r.unmappedstatus()).isEqualTo("initiated");
        assertThat(r.additionalCharges()).isNull();
    }

    @Test
    void notFound_isFoundFalse() {
        VerifyResult r = PayuClient.parse(NOT_FOUND, "T3").orElseThrow();
        assertThat(r.found()).isFalse();
    }

    @Test
    void garbageEmptyOrWrongTxnid_isUnknownNotAnException() {
        assertThat(PayuClient.parse("<html>429</html>", "T1")).isEmpty();
        assertThat(PayuClient.parse(null, "T1")).isEmpty();
        assertThat(PayuClient.parse("", "T1")).isEmpty();
        assertThat(PayuClient.parse(CAPTURED, "OTHER")).isEmpty();
        assertThat(PayuClient.parse("{\"status\":0,\"msg\":\"bad hash\"}", "T1")).isEmpty();
    }
}
