package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.featureflag.FeatureFlag;
import com.navix.common.featureflag.FeatureFlagRepository;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IntakeSwitchServiceTest {

    @Mock
    private FeatureFlagRepository repository;

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    @Test
    void nonAdminIsRefusedOnBothStatusAndSet() {
        IntakeSwitchService service = new IntakeSwitchService(repository);
        ActorContext.set(new CurrentActor("9", "9", "CREDIT_HEAD"));

        assertThatThrownBy(() -> service.setClosed(true)).isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "FORBIDDEN_ROLE");
        assertThatThrownBy(service::status).isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "FORBIDDEN_ROLE");
        verify(repository, never()).save(any());
    }

    @Test
    void adminUpsertsTheFlag_andMissingRowReadsAsOpen() {
        IntakeSwitchService service = new IntakeSwitchService(repository);
        ActorContext.set(new CurrentActor("1", "1", "ADMIN"));
        when(repository.findByFlagKey(IntakeSwitchService.FLAG)).thenReturn(Optional.empty());
        when(repository.save(any(FeatureFlag.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service.status().closed()).isFalse();
        assertThat(service.setClosed(true).closed()).isTrue();
    }
}
