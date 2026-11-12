package com.navix.iam.service;

import com.navix.common.blocklist.BlocklistKey;
import com.navix.common.blocklist.BlocklistMirror;
import com.navix.iam.domain.BlocklistType;
import com.navix.iam.entity.BlocklistEntry;
import com.navix.iam.repository.BlocklistEntryRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * IAM-backed implementation of the {@link BlocklistMirror} port: lets {@code navix-loan} block,
 * screen and unblock customers on {@code blocklist_entry} without the ADMIN guard
 * {@link BlocklistService} puts on its own console actions — the caller has already authorized it.
 */
@Component
@RequiredArgsConstructor
public class BlocklistMirrorAdapter implements BlocklistMirror {

    private final BlocklistEntryRepository blocklistRepository;

    @Override
    @Transactional
    public void block(String type, String value, String reason, Long customerId) {
        BlocklistType parsed = parse(type);
        BlocklistKey key = BlocklistKey.of(type, value);
        if (parsed == null || key == null) {
            return;
        }
        BlocklistEntry entry = blocklistRepository.findByTypeAndValue(parsed, key.value())
                .orElseGet(BlocklistEntry::new);
        // An entry that is already active belongs to whoever created it (usually an ADMIN on the
        // blocklist page): leave its reason alone. It already blocks this customer either way.
        if (entry.getId() != null && entry.isActive()) {
            return;
        }
        entry.setType(parsed);
        entry.setValue(key.value());
        entry.setReason(reason);
        entry.setCustomerId(customerId);
        entry.setActive(true);
        blocklistRepository.save(entry);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> firstActiveMatch(Collection<BlocklistKey> keys) {
        for (BlocklistKey key : keys) {
            BlocklistType parsed = key == null ? null : parse(key.type());
            if (parsed != null && blocklistRepository.existsByTypeAndValueAndActiveTrue(parsed, key.value())) {
                return Optional.of(key.type());
            }
        }
        return Optional.empty();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveLinked(Long customerId) {
        return customerId != null && blocklistRepository.existsByCustomerIdAndActiveTrue(customerId);
    }

    @Override
    @Transactional
    public int deactivateAll(Long customerId, Collection<BlocklistKey> keys) {
        Map<Long, BlocklistEntry> hits = new LinkedHashMap<>();
        if (customerId != null) {
            blocklistRepository.findByCustomerIdAndActiveTrue(customerId).forEach(e -> hits.put(e.getId(), e));
        }
        keys.stream().filter(Objects::nonNull).forEach(key -> {
            BlocklistType parsed = parse(key.type());
            if (parsed != null) {
                blocklistRepository.findByTypeAndValue(parsed, key.value())
                        .filter(BlocklistEntry::isActive)
                        .ifPresent(e -> hits.put(e.getId(), e));
            }
        });
        hits.values().forEach(e -> {
            e.setActive(false);
            blocklistRepository.save(e);
        });
        return hits.size();
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
