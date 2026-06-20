package com.navix.iam.controller;

import com.navix.iam.service.BlocklistService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for the fraud blocklist (PAN, Aadhaar reference, phone,
 * device, bank account). ADMIN role only.
 *
 * TODO: secure with ADMIN role; define request/response DTOs.
 */
@RestController
@RequestMapping("/api/admin/blocklist")
@RequiredArgsConstructor
public class BlocklistController {

    private final BlocklistService blocklistService;

    /** List active blocklist entries. */
    @GetMapping
    public Object list() {
        // TODO: return blocklistService.listActive() mapped to a response DTO.
        throw new UnsupportedOperationException("BlocklistController.list not implemented yet");
    }

    /** Add a blocklist entry. */
    @PostMapping
    public Object add() {
        // TODO: accept AddBlocklistRequest(type, value, reason) and delegate.
        throw new UnsupportedOperationException("BlocklistController.add not implemented yet");
    }

    /** Deactivate a blocklist entry. */
    @DeleteMapping("/{id}")
    public void remove(@PathVariable Long id) {
        blocklistService.remove(id);
    }
}
