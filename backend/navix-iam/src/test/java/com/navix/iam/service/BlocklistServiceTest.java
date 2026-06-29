package com.navix.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.iam.domain.BlocklistType;
import com.navix.iam.entity.BlocklistEntry;
import com.navix.iam.repository.BlocklistEntryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlocklistServiceTest {

    @Mock
    private BlocklistEntryRepository blocklistRepository;

    private BlocklistService blocklistService;

    @BeforeEach
    void setUp() {
        blocklistService = new BlocklistService(blocklistRepository);
        // add/remove/listActive are ADMIN-only; default the actor to ADMIN (isBlocked stays open).
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    @Test
    void mutationsRejectNonAdmin() {
        ActorContext.set(new CurrentActor("9", "Officer", "COLLECTION_EXECUTIVE"));
        assertThatThrownBy(() -> blocklistService.add(BlocklistType.PAN, "ABCDE1234F", "x"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("FORBIDDEN_ROLE");
        assertThatThrownBy(() -> blocklistService.listActive())
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("FORBIDDEN_ROLE");
    }

    @Test
    void isBlockedDelegatesToActiveExistenceCheck() {
        when(blocklistRepository.existsByTypeAndValueAndActiveTrue(BlocklistType.PAN, "ABCDE1234F"))
                .thenReturn(true);

        assertThat(blocklistService.isBlocked(BlocklistType.PAN, "ABCDE1234F")).isTrue();
        assertThat(blocklistService.isBlocked(BlocklistType.PHONE, "9999999999")).isFalse();
    }

    @Test
    void addCreatesNewActiveEntry() {
        when(blocklistRepository.findByTypeAndValue(BlocklistType.PHONE, "9999999999"))
                .thenReturn(Optional.empty());
        when(blocklistRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        BlocklistEntry entry = blocklistService.add(BlocklistType.PHONE, "9999999999", "fraud");

        assertThat(entry.getType()).isEqualTo(BlocklistType.PHONE);
        assertThat(entry.getValue()).isEqualTo("9999999999");
        assertThat(entry.getReason()).isEqualTo("fraud");
        assertThat(entry.isActive()).isTrue();
    }

    @Test
    void addReactivatesExistingEntryInsteadOfDuplicating() {
        BlocklistEntry existing = new BlocklistEntry();
        existing.setId(7L);
        existing.setType(BlocklistType.PAN);
        existing.setValue("ABCDE1234F");
        existing.setActive(false);
        when(blocklistRepository.findByTypeAndValue(BlocklistType.PAN, "ABCDE1234F"))
                .thenReturn(Optional.of(existing));
        when(blocklistRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        BlocklistEntry entry = blocklistService.add(BlocklistType.PAN, "ABCDE1234F", "re-flag");

        assertThat(entry.getId()).isEqualTo(7L);
        assertThat(entry.isActive()).isTrue();
        assertThat(entry.getReason()).isEqualTo("re-flag");
    }

    @Test
    void removeDeactivatesEntry() {
        BlocklistEntry existing = new BlocklistEntry();
        existing.setId(7L);
        existing.setActive(true);
        when(blocklistRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(blocklistRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        blocklistService.remove(7L);

        assertThat(existing.isActive()).isFalse();
    }

    @Test
    void removeNotFoundThrows() {
        when(blocklistRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> blocklistService.remove(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("BlocklistEntry");
        verify(blocklistRepository, never()).save(any());
    }

    @Test
    void listActiveReturnsActiveEntries() {
        BlocklistEntry e = new BlocklistEntry();
        e.setId(1L);
        e.setActive(true);
        when(blocklistRepository.findByActiveTrue()).thenReturn(List.of(e));

        assertThat(blocklistService.listActive()).containsExactly(e);
    }
}
