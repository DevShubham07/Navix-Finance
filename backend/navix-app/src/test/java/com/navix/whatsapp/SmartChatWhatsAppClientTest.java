package com.navix.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Envelopes captured from the live SmartChat API on 2026-09-23. */
class SmartChatWhatsAppClientTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void readsTemplateAndAuthSuccessEnvelopes() throws Exception {
        assertThat(SmartChatWhatsAppClient.requestId(json.readTree(
                "{\"status\":200,\"messsage\":\"Message Send Successfully\",\"message_id\":\"wamid.A\"}")))
                .isEqualTo("wamid.A");
        assertThat(SmartChatWhatsAppClient.requestId(json.readTree(
                "{\"result\":\"true\",\"message\":\"success\",\"request_id\":\"wamid.B\"}")))
                .isEqualTo("wamid.B");
    }

    @Test
    void throwsProviderMessageOnError() throws Exception {
        assertThatThrownBy(() -> SmartChatWhatsAppClient.requestId(json.readTree(
                "{\"result\":\"false\",\"message\":\"Invalid Template Name.\"}")))
                .hasMessageContaining("Invalid Template Name.");
    }
}
