package com.navix.contact;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request/response records for the public marketing "Contact us" form. */
public final class ContactDtos {

    private ContactDtos() {
    }

    /**
     * A visitor's message from the marketing contact page. {@code name}, {@code email} and
     * {@code message} are required; {@code phone} and {@code topic} are optional. The whole payload is
     * emailed to the NAVIX support inbox — nothing is persisted.
     */
    public record ContactRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Email @Size(max = 190) String email,
            @Size(max = 40) String phone,
            @Size(max = 60) String topic,
            @NotBlank @Size(max = 4000) String message) {
    }

    /** A generic acknowledgement shown back to the visitor. */
    public record ContactResponse(String message) {
    }
}
