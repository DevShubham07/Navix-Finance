package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.dto.MessagingDtos.StoredTemplateRequest;
import com.navix.loan.dto.MessagingDtos.StoredTemplateView;
import com.navix.loan.dto.MessagingDtos.WhatsAppRefreshResult;
import com.navix.loan.service.MessageTemplateService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN Templates library (V97). The role and flag checks live in the service. */
@RestController
@RequestMapping("/api/customer-messages/admin-templates")
@RequiredArgsConstructor
public class MessageTemplateController {

    private final MessageTemplateService service;

    @GetMapping
    public ApiResponse<List<StoredTemplateView>> list(@RequestParam String channel) {
        return ApiResponse.ok(service.list(channel));
    }

    @PostMapping
    public ApiResponse<StoredTemplateView> create(@Valid @RequestBody StoredTemplateRequest req) {
        return ApiResponse.ok(service.create(req));
    }

    @PostMapping("/{id}")
    public ApiResponse<StoredTemplateView> update(@PathVariable Long id, @Valid @RequestBody StoredTemplateRequest req) {
        return ApiResponse.ok(service.update(id, req));
    }

    @PostMapping("/whatsapp/refresh")
    public ApiResponse<WhatsAppRefreshResult> refreshWhatsApp() {
        return ApiResponse.ok(service.refreshWhatsApp());
    }
}
