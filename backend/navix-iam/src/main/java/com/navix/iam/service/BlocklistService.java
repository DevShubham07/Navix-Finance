package com.navix.iam.service;

import com.navix.common.blocklist.BlocklistEntryRemovedEvent;
import com.navix.common.blocklist.BlocklistKey;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.security.ActorContext;
import com.navix.iam.domain.BlocklistType;
import com.navix.iam.entity.BlocklistEntry;
import com.navix.iam.repository.BlocklistEntryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fraud blocklist screening + administration, backed by {@link BlocklistEntryRepository}.
 *
 * <p>Values are normalised on the way in and on every lookup ({@link BlocklistKey#normalize}), so a
 * PAN typed in lowercase or a phone typed with +91 still matches. Screening of new applications
 * happens in {@code navix-loan} through the {@code BlocklistMirror} port; removing an entry linked to
 * a customer publishes {@link BlocklistEntryRemovedEvent} so their staff block can be lifted.
 */
@Service
@RequiredArgsConstructor
public class BlocklistService {

    private final BlocklistEntryRepository blocklistRepository;
    private final ApplicationEventPublisher events;

    /** True if the given identifier is currently (actively) blocklisted. */
    @Transactional(readOnly = true)
    public boolean isBlocked(BlocklistType type, String value) {
        String normalized = BlocklistKey.normalize(type.name(), value);
        return normalized != null && blocklistRepository.existsByTypeAndValueAndActiveTrue(type, normalized);
    }

    /**
     * Add a blocklist entry — or reactivate (and re-reason) an existing one for the same
     * type+value rather than creating a duplicate.
     */
    @Transactional
    public BlocklistEntry add(BlocklistType type, String value, String reason) {
        requireAdmin();
        String normalized = type == null ? null : BlocklistKey.normalize(type.name(), value);
        if (normalized == null) {
            throw new BusinessException("BLOCKLIST_VALUE_REQUIRED", "Enter the value to block");
        }
        BlocklistEntry entry = blocklistRepository.findByTypeAndValue(type, normalized)
                .orElseGet(BlocklistEntry::new);
        entry.setType(type);
        entry.setValue(normalized);
        entry.setReason(reason);
        entry.setActive(true);
        return blocklistRepository.save(entry);
    }

    /** Deactivate a blocklist entry by id (soft remove: {@code active = false}). */
    @Transactional
    public void remove(Long id) {
        requireAdmin();
        BlocklistEntry entry = blocklistRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BlocklistEntry", String.valueOf(id)));
        entry.setActive(false);
        blocklistRepository.save(entry);
        if (entry.getCustomerId() != null) {
            events.publishEvent(new BlocklistEntryRemovedEvent(entry.getCustomerId()));
        }
    }

    /** List all active blocklist entries. */
    @Transactional(readOnly = true)
    public List<BlocklistEntry> listActive() {
        requireAdmin();
        return blocklistRepository.findByActiveTrue();
    }

    /** Guard: only an ADMIN may add/remove/list blocklist entries ({@link #isBlocked} stays open
     *  for the internal sign-up / pre-approval screening path). */
    private void requireAdmin() {
        if (!"ADMIN".equals(ActorContext.get().role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ADMIN");
        }
    }
}
