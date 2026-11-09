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
    void parsesTheTemplateListAndRefusesAnErrorEnvelope() throws Exception {
        var list = SmartChatWhatsAppClient.parseTemplates(json.readTree(
                "[{\"template_name\":\"db_a_164\",\"template_status\":\"APPROVED\",\"rejected_reason\":null}]"));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).name()).isEqualTo("db_a_164");
        assertThat(list.get(0).status()).isEqualTo("APPROVED");
        assertThat(list.get(0).rejectedReason()).isNull();
        assertThatThrownBy(() -> SmartChatWhatsAppClient.parseTemplates(json.readTree(
                "{\"result\":\"false\",\"message\":\"Token is missing\"}")))
                .isInstanceOf(SmartChatWhatsAppClient.WhatsAppException.class);
    }

    @Test
    void throwsProviderMessageOnError() throws Exception {
        assertThatThrownBy(() -> SmartChatWhatsAppClient.requestId(json.readTree(
                "{\"result\":\"false\",\"message\":\"Invalid Template Name.\"}")))
                .hasMessageContaining("Invalid Template Name.");
    }
}
