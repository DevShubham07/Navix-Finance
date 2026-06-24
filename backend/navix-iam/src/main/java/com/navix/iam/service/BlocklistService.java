package com.navix.iam.service;

import com.navix.common.exception.ResourceNotFoundException;
import com.navix.iam.domain.BlocklistType;
import com.navix.iam.entity.BlocklistEntry;
import com.navix.iam.repository.BlocklistEntryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fraud blocklist screening + administration, backed by {@link BlocklistEntryRepository}.
 *
 * <p>{@link #isBlocked} is called both at sign-up and before approval; a match must stop the
 * application. It uses the repository's active-only existence check so deactivated entries no
 * longer block.
 */
@Service
@RequiredArgsConstructor
public class BlocklistService {

    private final BlocklistEntryRepository blocklistRepository;

    /** True if the given identifier is currently (actively) blocklisted. */
    @Transactional(readOnly = true)
    public boolean isBlocked(BlocklistType type, String value) {
        return blocklistRepository.existsByTypeAndValueAndActiveTrue(type, value);
    }

    /**
     * Add a blocklist entry — or reactivate (and re-reason) an existing one for the same
     * type+value rather than creating a duplicate.
     */
    @Transactional
    public BlocklistEntry add(BlocklistType type, String value, String reason) {
        BlocklistEntry entry = blocklistRepository.findByTypeAndValue(type, value)
                .orElseGet(BlocklistEntry::new);
        entry.setType(type);
        entry.setValue(value);
        entry.setReason(reason);
        entry.setActive(true);
        return blocklistRepository.save(entry);
    }

    /** Deactivate a blocklist entry by id (soft remove: {@code active = false}). */
    @Transactional
    public void remove(Long id) {
        BlocklistEntry entry = blocklistRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BlocklistEntry", String.valueOf(id)));
        entry.setActive(false);
        blocklistRepository.save(entry);
    }

    /** List all active blocklist entries. */
    @Transactional(readOnly = true)
    public List<BlocklistEntry> listActive() {
        return blocklistRepository.findByActiveTrue();
    }
}
