package com.navix.iam.service;

import com.navix.iam.domain.BlocklistType;
import com.navix.iam.entity.BlocklistEntry;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Fraud blocklist screening + administration.
 *
 * <p>{@link #isBlocked} is called both at sign-up and before approval; a match
 * must stop the application.
 *
 * TODO: back with BlocklistEntryRepository.
 */
@Service
public class BlocklistService {

    /** True if the given identifier is actively blocklisted. */
    public boolean isBlocked(BlocklistType type, String value) {
        // TODO: delegate to repository.existsByTypeAndValueAndActiveTrue.
        throw new UnsupportedOperationException("BlocklistService.isBlocked not implemented yet");
    }

    /** Add (or reactivate) a blocklist entry. */
    public BlocklistEntry add(BlocklistType type, String value, String reason) {
        // TODO: persist a new active entry.
        throw new UnsupportedOperationException("BlocklistService.add not implemented yet");
    }

    /** Deactivate a blocklist entry by id. */
    public void remove(Long id) {
        // TODO: set active = false.
        throw new UnsupportedOperationException("BlocklistService.remove not implemented yet");
    }

    /** List active blocklist entries. */
    public List<BlocklistEntry> listActive() {
        // TODO: return active entries.
        throw new UnsupportedOperationException("BlocklistService.listActive not implemented yet");
    }
}
