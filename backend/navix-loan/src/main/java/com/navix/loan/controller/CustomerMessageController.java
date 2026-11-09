package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.dto.MessagingDtos.LogPage;
import com.navix.loan.dto.MessagingDtos.PreviewRequest;
import com.navix.loan.dto.MessagingDtos.PreviewView;
import com.navix.loan.dto.MessagingDtos.SendRequest;
import com.navix.loan.dto.MessagingDtos.SendResult;
import com.navix.loan.dto.MessagingDtos.TemplateView;
import com.navix.loan.service.CustomerMessageService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only customer messaging (V95). The role check lives in the service. One recipient per send. */
@RestController
@RequestMapping("/api/customer-messages")
@RequiredArgsConstructor
public class CustomerMessageController {

    private final CustomerMessageService service;

    @GetMapping("/templates")
    public ApiResponse<List<TemplateView>> templates() {
        return ApiResponse.ok(service.templates());
    }

    @PostMapping("/preview")
    public ApiResponse<PreviewView> preview(@Valid @RequestBody PreviewRequest req) {
        return ApiResponse.ok(service.preview(req));
    }

    @PostMapping("/send")
    public ApiResponse<SendResult> send(@Valid @RequestBody SendRequest req) {
        return ApiResponse.ok(service.send(req));
    }

    @GetMapping("/log")
    public ApiResponse<LogPage> log(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String templateKey,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(service.log(page, size, customerId, templateKey, channel, status, from, to));
    }
}
