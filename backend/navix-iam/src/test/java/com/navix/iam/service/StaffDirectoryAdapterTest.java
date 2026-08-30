package com.navix.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.staff.StaffSummary;
import com.navix.iam.domain.StaffRole;
import com.navix.iam.domain.StaffStatus;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link StaffDirectoryAdapter#findStaffByIds} — the batched lookup behind {@code namesFor}. */
@ExtendWith(MockitoExtension.class)
class StaffDirectoryAdapterTest {

    @Mock
    private StaffUserRepository staffUserRepository;

    private StaffDirectoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new StaffDirectoryAdapter(staffUserRepository);
    }

    private static StaffUser staff(long id, String name, StaffRole role, StaffStatus status) {
        StaffUser s = new StaffUser();
        s.setId(id);
        s.setName(name);
        s.setRole(role);
        s.setStatus(status);
        s.setEmail(name.toLowerCase().replace(" ", ".") + "@navix.example");
        return s;
    }

    @Test
    void resolvesOnlyTheIdsThatExist() {
        when(staffUserRepository.findAllById(anyList())).thenReturn(List.of(
                staff(9L, "Sana Khan", StaffRole.COLLECTION_EXECUTIVE, StaffStatus.ACTIVE),
                staff(8L, "Arjun Patel", StaffRole.COLLECTION_HEAD, StaffStatus.ACTIVE)));

        // 404 does not resolve to anything, and is simply absent from the result.
        Map<Long, StaffSummary> result = adapter.findStaffByIds(List.of(9L, 8L, 404L));

        assertThat(result).hasSize(2);
        assertThat(result.get(9L).name()).isEqualTo("Sana Khan");
        assertThat(result.get(8L).name()).isEqualTo("Arjun Patel");
        assertThat(result).doesNotContainKey(404L);
    }

    @Test
    void emptyInputYieldsAnEmptyMutableMapThatToleratesGetNull() {
        Map<Long, StaffSummary> result = adapter.findStaffByIds(List.of());

        assertThat(result).isEmpty();
        // Map.of().get(null) throws NPE — callers legitimately look up an unassigned/null id.
        assertThatCode(() -> result.get(null)).doesNotThrowAnyException();
    }

    @Test
    void nullInputYieldsAnEmptyMutableMap() {
        Map<Long, StaffSummary> result = adapter.findStaffByIds(null);

        assertThat(result).isEmpty();
        assertThatCode(() -> result.get(null)).doesNotThrowAnyException();
    }

    @Test
    void callsFindAllByIdExactlyOnceWithDistinctNonNullIds() {
        when(staffUserRepository.findAllById(anyList())).thenReturn(List.of(
                staff(9L, "Sana Khan", StaffRole.COLLECTION_EXECUTIVE, StaffStatus.ACTIVE)));

        adapter.findStaffByIds(java.util.Arrays.asList(9L, 9L, null, 9L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(staffUserRepository).findAllById(captor.capture());
        assertThat(captor.getValue()).containsExactly(9L);
    }
}
