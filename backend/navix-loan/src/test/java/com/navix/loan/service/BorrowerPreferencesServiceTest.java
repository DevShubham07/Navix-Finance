package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.entity.BorrowerPreferences;
import com.navix.loan.repository.BorrowerPreferencesRepository;
import com.navix.loan.service.BorrowerPreferencesService.PreferencesView;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BorrowerPreferencesServiceTest {

    @Mock private BorrowerPreferencesRepository repository;

    private BorrowerPreferencesService service() {
        return new BorrowerPreferencesService(repository);
    }

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    @Test
    void defaultsAllOnWhenNoRowSaved() {
        ActorContext.set(new CurrentActor("9001", "Asha", "BORROWER"));
        when(repository.findByCustomerId(9001L)).thenReturn(Optional.empty());

        PreferencesView v = service().getMine();

        assertThat(v.emailOptIn()).isTrue();
        assertThat(v.smsOptIn()).isTrue();
        assertThat(v.offersOptIn()).isTrue();
    }

    @Test
    void updateUpsertsForCallingBorrower() {
        ActorContext.set(new CurrentActor("9001", "Asha", "BORROWER"));
        when(repository.findByCustomerId(9001L)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        PreferencesView v = service().updateMine(new PreferencesView(false, true, false));

        assertThat(v.emailOptIn()).isFalse();
        assertThat(v.smsOptIn()).isTrue();
        assertThat(v.offersOptIn()).isFalse();
    }

    @Test
    void rejectsNonBorrowerActor() {
        ActorContext.set(new CurrentActor("3", "Admin", "ADMIN"));
        assertThatThrownBy(() -> service().getMine())
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("FORBIDDEN_ROLE");
    }
}
