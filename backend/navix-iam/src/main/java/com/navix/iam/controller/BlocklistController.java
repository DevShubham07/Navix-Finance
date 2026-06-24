package com.navix.iam.controller;

import com.navix.common.web.ApiResponse;
import com.navix.iam.dto.StaffDtos.AddBlocklistRequest;
import com.navix.iam.dto.StaffDtos.BlocklistResponse;
import com.navix.iam.service.BlocklistService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for the fraud blocklist (PAN, Aadhaar reference, phone, device, bank account).
 * RBAC (ADMIN-only) is deferred to go-live (handoff §0.1).
 */
@RestController
@RequestMapping("/api/admin/blocklist")
@RequiredArgsConstructor
public class BlocklistController {

    private final BlocklistService blocklistService;

    /** List active blocklist entries. */
    @GetMapping
    public ApiResponse<List<BlocklistResponse>> list() {
        return ApiResponse.ok(blocklistService.listActive().stream()
                .map(BlocklistResponse::of)
                .toList());
    }

    /** Add (or reactivate) a blocklist entry. */
    @PostMapping
    public ApiResponse<BlocklistResponse> add(@Valid @RequestBody AddBlocklistRequest request) {
        return ApiResponse.ok(BlocklistResponse.of(
                blocklistService.add(request.type(), request.value(), request.reason())));
    }

    /** Deactivate a blocklist entry. */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        blocklistService.remove(id);
        return ApiResponse.ok(null);
    }
}
