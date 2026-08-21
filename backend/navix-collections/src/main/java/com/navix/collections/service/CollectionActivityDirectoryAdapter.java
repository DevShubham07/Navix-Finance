package com.navix.collections.service;

import com.navix.collections.repository.InteractionLogRepository;
import com.navix.common.collections.CollectionActivityDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Collections-module implementation of the {@link CollectionActivityDirectory} port: counts a whole
 * roster's logged interactions in one grouped query. Mirrors {@link CollectionCaseDirectoryAdapter}
 * — wired by component scan, consumed by the loan module's staff-performance dashboard so the
 * "calls" figure can include collections activity without navix-loan depending on navix-collections.
 */
@Component
@RequiredArgsConstructor
public class CollectionActivityDirectoryAdapter implements CollectionActivityDirectory {

    private final InteractionLogRepository interactionRepository;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Long> callCountsByStaff(Collection<Long> staffIds, Instant from, Instant to) {
        if (staffIds == null || staffIds.isEmpty() || from == null || to == null) {
            return Map.of();
        }
        Map<Long, Long> result = new HashMap<>();
        for (InteractionLogRepository.StaffCallCount row
                : interactionRepository.countByStaffInWindow(staffIds, from, to)) {
            result.put(row.getStaffId(), row.getCount());
        }
        return result;
    }
}
