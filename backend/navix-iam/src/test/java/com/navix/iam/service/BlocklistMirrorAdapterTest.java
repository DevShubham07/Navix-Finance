package com.navix.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.iam.domain.BlocklistType;
import com.navix.iam.entity.BlocklistEntry;
import com.navix.iam.repository.BlocklistEntryRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** A staff block mirrors onto the blocklist without ever taking over an entry someone else owns. */
@ExtendWith(MockitoExtension.class)
class BlocklistMirrorAdapterTest {

    @Mock private BlocklistEntryRepository repository;
    @InjectMocks private BlocklistMirrorAdapter adapter;

    private static BlocklistEntry existing(boolean active, String reason) {
        BlocklistEntry e = new BlocklistEntry();
        ReflectionTestUtils.setField(e, "id", 5L);
        e.setType(BlocklistType.PHONE);
        e.setValue("9876543210");
        e.setReason(reason);
        e.setActive(active);
        return e;
    }

    @Test
    void createsAnEntryWhenNoneExists() {
        when(repository.findByTypeAndValue(BlocklistType.PHONE, "9876543210")).thenReturn(Optional.empty());
        adapter.block("PHONE", "9876543210", "Blocked by staff: bounced");
        ArgumentCaptor<BlocklistEntry> saved = ArgumentCaptor.forClass(BlocklistEntry.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().isActive()).isTrue();
        assertThat(saved.getValue().getReason()).isEqualTo("Blocked by staff: bounced");
    }

    @Test
    void leavesAnActiveAdminEntryUntouchedSoUnblockCannotLiftIt() {
        BlocklistEntry admin = existing(true, "Fraud ring — ADMIN");
        when(repository.findByTypeAndValue(BlocklistType.PHONE, "9876543210")).thenReturn(Optional.of(admin));

        adapter.block("PHONE", "9876543210", "Blocked by staff: bounced");
        verify(repository, never()).save(any());
        assertThat(admin.getReason()).isEqualTo("Fraud ring — ADMIN");

        adapter.unblock("PHONE", "9876543210", "Blocked by staff: bounced");
        assertThat(admin.isActive()).isTrue();
        verify(repository, never()).save(any());
    }

    @Test
    void reactivatesAnInactiveEntryAsItsOwn() {
        BlocklistEntry old = existing(false, "Lifted long ago");
        when(repository.findByTypeAndValue(BlocklistType.PHONE, "9876543210")).thenReturn(Optional.of(old));
        adapter.block("PHONE", "9876543210", "Blocked by staff: bounced");
        assertThat(old.isActive()).isTrue();
        assertThat(old.getReason()).isEqualTo("Blocked by staff: bounced");
        verify(repository).save(old);
    }
}
