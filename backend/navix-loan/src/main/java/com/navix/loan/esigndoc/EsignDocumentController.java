package com.navix.loan.esigndoc;

import com.navix.common.web.ApiResponse;
import com.navix.loan.esigndoc.EsignDocumentDtos.CreateRequest;
import com.navix.loan.esigndoc.EsignDocumentDtos.Detail;
import com.navix.loan.esigndoc.EsignDocumentDtos.Summary;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only eSign documents. Every operation is role- and flag-gated in EsignDocumentService. */
@RestController
@RequestMapping("/api/admin/esign-documents")
@RequiredArgsConstructor
public class EsignDocumentController {

    private final EsignDocumentService service;

    @GetMapping
    public ApiResponse<List<Summary>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Detail> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    public ApiResponse<Detail> create(@RequestBody CreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PostMapping("/{id}/refresh")
    public ApiResponse<Detail> refresh(@PathVariable Long id) {
        return ApiResponse.ok(service.refresh(id));
    }

    @PostMapping("/{id}/signers/{signerId}/resend")
    public ApiResponse<Detail> resend(@PathVariable Long id, @PathVariable Long signerId) {
        return ApiResponse.ok(service.resend(id, signerId));
    }
}
