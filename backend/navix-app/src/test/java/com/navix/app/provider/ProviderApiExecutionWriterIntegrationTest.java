package com.navix.app.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.verification.support.ProviderCall;
import com.navix.common.verification.ProviderCallContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The provider-call audit trail against a real Flyway-migrated Postgres.
 *
 * <p>The rollback test is the important one: the provider call happens INSIDE the verification
 * transaction, and an administrator investigating a failure needs the evidence precisely when that
 * transaction did not commit. If {@code REQUIRES_NEW} ever stops taking effect — the classic way
 * being a self-invocation that bypasses the Spring proxy — this test fails and nothing else would.
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
class ProviderApiExecutionWriterIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired ProviderApiExecutionWriter writer;
    @Autowired ProviderApiExecutionRepository repository;
    @Autowired TransactionTemplate transactions;

    @AfterEach
    void clearContext() {
        ProviderCallContext.clear();
    }

    private static ProviderCall call(String endpoint, String requestJson, String responseJson) {
        return new ProviderCall("DIGITAP", "BUREAU", endpoint, requestJson, responseJson,
                200, 1234L, ProviderCall.SUCCESS, null);
    }

    @Test
    void theAuditRowSurvivesARollbackOfTheSurroundingTransaction() {
        Long id = transactions.execute(status -> {
            Long written = writer.write(call("/credit_analytics/request",
                    "{\"client_ref_num\":\"navix-116-BUREAU\"}", "{\"result_code\":101}"));
            status.setRollbackOnly();
            return written;
        });

        assertThat(id).isNotNull();
        assertThat(repository.findById(id)).isPresent();
    }

    @Test
    void attributesTheCallToItsApplicationAndCheckTypeFromTheClientRef() {
        Long id = writer.write(call("/credit_analytics/request",
                "{\"client_ref_num\":\"navix-116-BUREAU\",\"pan\":\"ABCDE1234F\"}", "{}"));

        ProviderApiExecution row = repository.findById(id).orElseThrow();
        assertThat(row.getApplicationId()).isEqualTo(116L);
        assertThat(row.getCheckType()).isEqualTo("BUREAU");
        // Nothing marked this thread as a manual run, so it is real traffic.
        assertThat(row.getSource()).isEqualTo(ProviderCallContext.LIVE);
        assertThat(row.getEndpoint()).isEqualTo("/credit_analytics/request");
        assertThat(row.getHttpStatus()).isEqualTo(200);
    }

    @Test
    void theThreadContextWinsOverTheClientRef() {
        ProviderCallContext.setApplicationId(9001L);
        ProviderCallContext.setCheckType("PENNY_DROP");
        ProviderCallContext.setSource(ProviderCallContext.MANUAL);

        ProviderApiExecution row = repository.findById(writer.write(call(
                "/credit_analytics/request", "{\"client_ref_num\":\"navix-116-BUREAU\"}", "{}"))).orElseThrow();

        assertThat(row.getApplicationId()).isEqualTo(9001L);
        assertThat(row.getCheckType()).isEqualTo("PENNY_DROP");
        assertThat(row.getSource()).isEqualTo(ProviderCallContext.MANUAL);
    }

    /** A truncated JSON document does not parse, so the fragment has to be wrapped as a JSON string. */
    @Test
    void anOversizedPayloadIsStoredAsValidJsonRatherThanFailingTheInsert() throws Exception {
        String huge = "{\"report\":\"" + "x".repeat(400_000) + "\"}";

        ProviderApiExecution row = repository.findById(
                writer.write(call("/credit_analytics/request", "{}", huge))).orElseThrow();

        // Postgres normalises jsonb (key order and spacing), so assert on the parsed document.
        JsonNode stored = new ObjectMapper().readTree(row.getResponseJson());
        assertThat(stored.path("__truncated").asBoolean()).isTrue();
        assertThat(stored.path("__originalChars").asInt()).isEqualTo(huge.length());
        assertThat(stored.path("__body").asText()).startsWith("{\"report\":\"xxx");
        assertThat(row.getResponseJson().length()).isLessThan(huge.length());
    }

    /**
     * A provider call whose failure is only discovered AFTER the row was written still has to end up
     * FAILED, carrying the client's own reason.
     *
     * <p>This is the shape of every silent provider outage we have had. Aadhaar eSign was rejected by
     * Signzy 156 times out of 156 across four weeks and raised nothing, because each failure was caught
     * and degraded into a drawn-signature fallback; the audit trail was the only place the truth could
     * have lived. It is also what makes {@code healthSince} trustworthy — that query reads
     * {@code status = 'SUCCESS'}, so a failure left recorded as a success is an outage the health sweep
     * would never see.
     *
     * <p>The reason is clamped because provider error bodies are routinely raw HTML and the column is
     * {@code varchar(2000)}: an unclamped write is an exception thrown while recording an exception.
     */
    @Test
    void markFailedUpdatesStatusAndError() {
        Long id = writer.write(call("/api/v3/contracts", "{\"client_ref_num\":\"navix-116-AGREEMENT\"}",
                "{\"status\":\"queued\"}"));
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(ProviderCall.SUCCESS);

        String htmlErrorBody = "<html><body>" + "callback url is required. ".repeat(200) + "</body></html>";
        writer.markFailed(id, htmlErrorBody);

        ProviderApiExecution row = repository.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ProviderCall.FAILED);
        assertThat(row.getErrorMessage()).hasSize(2000);
        assertThat(row.getErrorMessage()).isEqualTo(htmlErrorBody.substring(0, 2000));
    }

    /** A row that has since aged out of the retention window is simply not there — not an error. */
    @Test
    void markFailedIgnoresAnIdThatNoLongerExists() {
        assertThatCode(() -> writer.markFailed(9_999_999L, "gone")).doesNotThrowAnyException();
        assertThatCode(() -> writer.markFailed(null, "no row was written")).doesNotThrowAnyException();
    }

    @Test
    void searchAppliesEveryFilterAndIgnoresTheOnesLeftNull() {
        writer.write(call("/credit_analytics/request", "{\"client_ref_num\":\"navix-501-BUREAU\"}", "{}"));
        ProviderCallContext.setApplicationId(502L);
        writer.write(new ProviderCall("SIGNZY", "PAN", "/api/v3/pan/compliance-206-individual-search",
                "{}", null, 403, 12L, ProviderCall.FAILED, "HTTP 403"));
        ProviderCallContext.clear();

        // No filters at all -> everything is visible.
        assertThat(repository.search(null, null, null, null, null, null, null, PageRequest.of(0, 50))
                .getTotalElements()).isGreaterThanOrEqualTo(2);

        assertThat(repository.search(null, null, null, null, 501L, null, null, PageRequest.of(0, 50))
                .getContent()).singleElement()
                .satisfies(r -> assertThat(r.getOperation()).isEqualTo("BUREAU"));

        assertThat(repository.search("SIGNZY", "PAN", "FAILED", ProviderCallContext.LIVE, 502L,
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.HOURS),
                PageRequest.of(0, 50)).getContent()).singleElement()
                .satisfies(r -> {
                    assertThat(r.getHttpStatus()).isEqualTo(403);
                    assertThat(r.getErrorMessage()).isEqualTo("HTTP 403");
                });

        // A filter that matches nothing must return nothing rather than everything.
        assertThat(repository.search("SIGNZY", "PAN", "SUCCESS", null, 502L, null, null,
                PageRequest.of(0, 50)).getContent()).isEmpty();
    }
}
