package com.navix.verification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.navix.verification.dto.FintrixDtos.PanResponse;
import com.navix.verification.exception.VerificationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Offline tests for {@link FintrixPanClient}: the {@code data}/{@code data.address} unwrap, the
 * top-level {@code transaction_id}, name trimming, the error-envelope-at-HTTP-200 branch, and — the one
 * that would otherwise only fail in production — that the leading-slash endpoint resolves underneath a
 * base URL that itself carries a path.
 */
class FintrixPanClientTest {

    private static final String BASE = "https://fintrix.test";

    /** The real production value, path suffix and all — see {@code application.yml}. */
    private static final String PROD_BASE = "https://admin.fintrix.tech/__api/api/v1/";

    private static final String SUCCESS_JSON = """
            {
              "timestamp": "2026-08-26T17:52:21.778Z",
              "transaction_id": "TXN-PROD-8a54b338",
              "status": "success",
              "request_id": "TXN-PROD-01M0ZK75",
              "data": {
                "pan_number": "QVEPS0901K",
                "status": "valid",
                "full_name": "  SHUBHAM",
                "dob": "2003-03-24",
                "gender": "M",
                "doi": null,
                "tax": true,
                "aadhaar_linked": true,
                "masked_aadhaar": "65XXXXXXXX90",
                "address": {
                  "state": "Haryana",
                  "zip": "131001",
                  "city": "SONIPAT",
                  "country": "INDIA"
                }
              }
            }
            """;

    private record Bound(MockRestServiceServer server, RestClient restClient) {
    }

    private Bound bind(String baseUrl) {
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Bound(server, builder.build());
    }

    @Test
    void unwrapsDataAndAddress_trimsPaddedName() {
        Bound b = bind(BASE);
        b.server().expect(requestTo(BASE + "/pan_comprehensive"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"id_number\":\"QVEPS0901K\",\"remark\":\"app-1\"}"))
                .andRespond(withSuccess(SUCCESS_JSON, MediaType.APPLICATION_JSON));

        PanResponse r = new FintrixPanClient(b.restClient()).verify("QVEPS0901K", "app-1");

        assertThat(r.txnId()).isEqualTo("TXN-PROD-8a54b338");
        assertThat(r.status()).isEqualTo("valid");
        // Fintrix pads the name; this value feeds the profile and recomputeNameMatch, so it must arrive trimmed.
        assertThat(r.fullName()).isEqualTo("SHUBHAM");
        assertThat(r.dob()).isEqualTo("2003-03-24");
        assertThat(r.gender()).isEqualTo("M");
        assertThat(r.aadhaarLinked()).isTrue();
        assertThat(r.maskedAadhaar()).isEqualTo("65XXXXXXXX90");
        assertThat(r.panNumber()).isEqualTo("QVEPS0901K");
        assertThat(r.allotmentDate()).isNull();
        assertThat(r.addressState()).isEqualTo("Haryana");
        assertThat(r.addressZip()).isEqualTo("131001");
        b.server().verify();
    }

    /**
     * The production base URL carries its own path ({@code /__api/api/v1/}) while the endpoint constant
     * starts with a slash. Spring's DefaultUriBuilderFactory APPENDS rather than resolving the endpoint
     * as root-absolute — pin that, because getting it wrong yields a 404 only in production, and it also
     * decides the URI string {@code ProviderCallCatalog} keys off.
     */
    @Test
    void leadingSlashEndpointResolvesUnderTheBaseUrlPath() {
        Bound b = bind(PROD_BASE);
        b.server().expect(requestTo("https://admin.fintrix.tech/__api/api/v1/pan_comprehensive"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(SUCCESS_JSON, MediaType.APPLICATION_JSON));

        new FintrixPanClient(b.restClient()).verify("QVEPS0901K", "app-1");

        b.server().verify();
    }

    /**
     * A rejected PAN comes back as an error envelope at HTTP 200. It must surface as a
     * VerificationException so the router falls through to Digitap rather than accepting a non-answer.
     */
    @Test
    void errorEnvelopeAtHttp200Throws() {
        Bound b = bind(BASE);
        b.server().expect(requestTo(BASE + "/pan_comprehensive"))
                .andRespond(withSuccess("""
                        {"status":"error","success":true,"error_message":"Invalid PAN",
                         "transaction_id":"TXN-f4bfb1c8","request_id":"TXN-PROD-01M0ZJ"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> new FintrixPanClient(b.restClient()).verify("", "app-1"))
                .isInstanceOf(VerificationException.class);
    }
}
