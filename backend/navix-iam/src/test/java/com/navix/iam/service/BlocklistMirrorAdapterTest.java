package com.navix.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.blocklist.BlocklistKey;
import com.navix.iam.domain.BlocklistType;
import com.navix.iam.entity.BlocklistEntry;
import com.navix.iam.repository.BlocklistEntryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** "Block customer" writes linked entries; screening and unblock read and clear the same entries. */
@ExtendWith(MockitoExtension.class)
class BlocklistMirrorAdapterTest {

    @Mock private BlocklistEntryRepository repository;
    @InjectMocks private BlocklistMirrorAdapter adapter;

    private static BlocklistEntry existing(long id, BlocklistType type, String value, boolean active, String reason) {
        BlocklistEntry e = new BlocklistEntry();
        ReflectionTestUtils.setField(e, "id", id);
        e.setType(type);
        e.setValue(value);
        e.setReason(reason);
        e.setActive(active);
        return e;
    }

    @Test
    void createsANormalisedEntryLinkedToTheCustomer() {
        when(repository.findByTypeAndValue(BlocklistType.PHONE, "9876543210")).thenReturn(Optional.empty());
        adapter.block("PHONE", "+91 98765 43210", "Blocked by staff: bounced", 42L);
        ArgumentCaptor<BlocklistEntry> saved = ArgumentCaptor.forClass(BlocklistEntry.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().isActive()).isTrue();
        assertThat(saved.getValue().getValue()).isEqualTo("9876543210");
        assertThat(saved.getValue().getCustomerId()).isEqualTo(42L);
        assertThat(saved.getValue().getReason()).isEqualTo("Blocked by staff: bounced");
    }

    @Test
    void leavesAnActiveAdminEntryAsItIs() {
        BlocklistEntry admin = existing(5L, BlocklistType.PHONE, "9876543210", true, "Fraud ring — ADMIN");
        when(repository.findByTypeAndValue(BlocklistType.PHONE, "9876543210")).thenReturn(Optional.of(admin));

        adapter.block("PHONE", "9876543210", "Blocked by staff: bounced", 42L);

        verify(repository, never()).save(any());
        assertThat(admin.getReason()).isEqualTo("Fraud ring — ADMIN");
        assertThat(admin.getCustomerId()).isNull();
    }

    @Test
    void reactivatesAnInactiveEntryForTheCustomer() {
        BlocklistEntry old = existing(5L, BlocklistType.PHONE, "9876543210", false, "Lifted long ago");
        when(repository.findByTypeAndValue(BlocklistType.PHONE, "9876543210")).thenReturn(Optional.of(old));
        adapter.block("PHONE", "9876543210", "Blocked by staff: bounced", 42L);
        assertThat(old.isActive()).isTrue();
        assertThat(old.getReason()).isEqualTo("Blocked by staff: bounced");
        assertThat(old.getCustomerId()).isEqualTo(42L);
        verify(repository).save(old);
    }

    @Test
    void firstActiveMatchReportsTheMatchingType() {
        when(repository.existsByTypeAndValueAndActiveTrue(BlocklistType.PHONE, "9876543210")).thenReturn(false);
        when(repository.existsByTypeAndValueAndActiveTrue(BlocklistType.PAN, "ABCDE1234F")).thenReturn(true);

        Optional<String> hit = adapter.firstActiveMatch(List.of(
                BlocklistKey.of("PHONE", "9876543210"), BlocklistKey.of("PAN", "abcde1234f")));

        assertThat(hit).contains("PAN");
    }

    @Test
    void deactivateAllClearsLinkedAndMatchingEntriesWhoeverAddedThem() {
        BlocklistEntry linked = existing(1L, BlocklistType.PHONE, "9876543210", true, "Blocked by staff: x");
        BlocklistEntry admin = existing(2L, BlocklistType.PAN, "ABCDE1234F", true, "ADMIN entry");
        when(repository.findByCustomerIdAndActiveTrue(42L)).thenReturn(List.of(linked));
        when(repository.findByTypeAndValue(BlocklistType.PHONE, "9876543210")).thenReturn(Optional.of(linked));
        when(repository.findByTypeAndValue(BlocklistType.PAN, "ABCDE1234F")).thenReturn(Optional.of(admin));

        int n = adapter.deactivateAll(42L, List.of(
                BlocklistKey.of("PHONE", "9876543210"), BlocklistKey.of("PAN", "ABCDE1234F")));

        assertThat(n).isEqualTo(2);
        assertThat(linked.isActive()).isFalse();
        assertThat(admin.isActive()).isFalse();
    }
}
