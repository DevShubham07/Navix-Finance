package com.navix.iam.service;

import com.navix.common.blocklist.BlocklistMirror;
import com.navix.iam.domain.BlocklistType;
import com.navix.iam.entity.BlocklistEntry;
import com.navix.iam.repository.BlocklistEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * IAM-backed implementation of the {@link BlocklistMirror} port: lets {@code navix-loan} mirror a
 * staff block onto {@code blocklist_entry} without the ADMIN guard {@link BlocklistService} puts on
 * its own console actions — the caller has already authorized the block.
 */
@Component
@RequiredArgsConstructor
public class BlocklistMirrorAdapter implements BlocklistMirror {

    private final BlocklistEntryRepository blocklistRepository;

    @Override
    @Transactional
    public void block(String type, String value, String reason) {
        BlocklistType parsed = parse(type);
        if (parsed == null || value == null || value.isBlank()) {
            return;
        }
        BlocklistEntry entry = blocklistRepository.findByTypeAndValue(parsed, value)
                .orElseGet(BlocklistEntry::new);
        // An entry that is already active belongs to whoever created it (usually an ADMIN on the
        // blocklist page): leave it alone. Rewriting its reason would make it look like ours, and a
        // later unblock — which only lifts entries carrying this block's reason — would then switch
        // off the ADMIN's entry too.
        if (entry.getId() != null && entry.isActive()) {
            return;
        }
        entry.setType(parsed);
        entry.setValue(value);
        entry.setReason(reason);
        entry.setActive(true);
        blocklistRepository.save(entry);
    }

    @Override
    @Transactional
    public void unblock(String type, String value, String reason) {
        BlocklistType parsed = parse(type);
        if (parsed == null || value == null || value.isBlank()) {
            return;
        }
        blocklistRepository.findByTypeAndValue(parsed, value)
                .filter(BlocklistEntry::isActive)
                .filter(e -> reason != null && reason.equals(e.getReason()))
                .ifPresent(e -> {
                    e.setActive(false);
                    blocklistRepository.save(e);
                });
    }

    private static BlocklistType parse(String type) {
        if (type == null) {
            return null;
        }
        try {
            return BlocklistType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
