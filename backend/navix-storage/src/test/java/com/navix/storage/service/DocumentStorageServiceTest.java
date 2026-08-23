package com.navix.storage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;

import com.navix.storage.config.StorageProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * Covers the {@link DocumentStorageService#storeFromUrl} size cap (plan.md §1): an over-cap
 * {@code Content-Length} is rejected without downloading, a body that exceeds the cap mid-stream
 * is rejected, and a normal small body still succeeds. Uses a local {@link HttpServer} stub — no
 * new test dependency.
 */
@ExtendWith(MockitoExtension.class)
class DocumentStorageServiceTest {

    @Mock
    private S3Client s3Client;

    private HttpServer server;
    private DocumentStorageService service;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        service = new DocumentStorageService(s3Client, null, new StorageProperties("bucket", 900, null));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void rejectsOverCapDeclaredContentLength_withoutDownloading() {
        server.createContext("/over-declared", exchange -> {
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(11L * 1024 * 1024));
            // Never actually write 11MB — if the service reads the body, this test would hang
            // or fail on stream mismatch, proving the cap short-circuited on the header alone.
            exchange.sendResponseHeaders(200, 11L * 1024 * 1024);
            exchange.close();
        });

        assertThatThrownBy(() -> service.storeFromUrl("key", baseUrl() + "/over-declared", "application/pdf"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds");
        verifyNoInteractions(s3Client);
    }

    @Test
    void rejectsBodyExceedingCap_whenContentLengthNotDeclared() throws IOException {
        byte[] oversized = new byte[(int) (10L * 1024 * 1024 + 1)];
        server.createContext("/over-actual", exchange -> {
            exchange.sendResponseHeaders(200, oversized.length);
            exchange.getResponseBody().write(oversized);
            exchange.close();
        });

        assertThatThrownBy(() -> service.storeFromUrl("key", baseUrl() + "/over-actual", "application/pdf"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds");
        verifyNoInteractions(s3Client);
    }

    @Test
    void storesNormalSmallBody() {
        byte[] payload = "hello world".getBytes(StandardCharsets.UTF_8);
        server.createContext("/small", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        org.mockito.Mockito.when(s3Client.putObject(any(software.amazon.awssdk.services.s3.model.PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String key = service.storeFromUrl("key", baseUrl() + "/small", "text/plain");

        assertThat(key).isEqualTo("key");
    }
}
