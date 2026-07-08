package com.navix.contact;

import com.navix.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoint behind the marketing "Contact us" form. Unauthenticated (a website visitor has no
 * session) — permitted in {@code SecurityConfig}. Emails the submission to the support inbox and returns
 * a generic acknowledgement the page shows the visitor.
 */
@RestController
@RequestMapping("/api/contact")
@RequiredArgsConstructor
public class ContactController {

    private final ContactService contactService;

    @PostMapping
    public ApiResponse<ContactDtos.ContactResponse> submit(@Valid @RequestBody ContactDtos.ContactRequest req) {
        contactService.submit(req);
        return ApiResponse.ok(new ContactDtos.ContactResponse(
                "Thanks — your message has been sent. Our team will get back to you as soon as possible."));
    }
}
