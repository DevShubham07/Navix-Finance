package com.navix.loan.payu;

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
class PayuSwitchServiceTest {

    private static final PayuProperties LIVE = new PayuProperties("key", "salt", "https://secure.payu.in/_payment",
            "https://info.payu.in/merchant/postservice.php?form=2", "https://x/api/payu/return", "", false);
    private static final PayuProperties BLANK = new PayuProperties("", "", "https://test.payu.in/_payment",
            "https://test.payu.in/merchant/postservice.php?form=2", "https://x/api/payu/return", "", false);

    @Mock
    private FeatureFlagRepository repository;

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    private static FeatureFlag flag(boolean on) {
        FeatureFlag f = new FeatureFlag();
        f.setFlagKey(PayuService.FLAG);
        f.setEnabled(on);
        return f;
    }

    @Test
    void nonAdminIsRefusedOnBothStatusAndSet() {
        PayuSwitchService service = new PayuSwitchService(repository, LIVE);
        ActorContext.set(new CurrentActor("9", "9", "ACCOUNTANT"));

        assertThatThrownBy(() -> service.setEnabled(false)).isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "FORBIDDEN_ROLE");
        assertThatThrownBy(service::status).isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "FORBIDDEN_ROLE");
        verify(repository, never()).save(any());
    }

    @Test
    void adminTurnsTheGatewayOffAndOnAgain() {
        PayuSwitchService service = new PayuSwitchService(repository, LIVE);
        ActorContext.set(new CurrentActor("1", "1", "ADMIN"));
        FeatureFlag row = flag(true);
        when(repository.findByFlagKey(PayuService.FLAG)).thenReturn(Optional.of(row));
        when(repository.save(any(FeatureFlag.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service.status().enabled()).isTrue();
        assertThat(service.setEnabled(false).enabled()).isFalse();
        assertThat(row.isEnabled()).isFalse();
        PayuSwitchService.GatewayStatus on = service.setEnabled(true);
        assertThat(on.enabled()).isTrue();
        assertThat(on.configured()).isTrue();
    }

    @Test
    void turningOnWithoutCredentialsIsRefused_butOffAlwaysWorks() {
        PayuSwitchService service = new PayuSwitchService(repository, BLANK);
        ActorContext.set(new CurrentActor("1", "1", "ADMIN"));

        assertThatThrownBy(() -> service.setEnabled(true)).isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "PAYU_NOT_CONFIGURED");
        verify(repository, never()).save(any());

        when(repository.findByFlagKey(PayuService.FLAG)).thenReturn(Optional.empty());
        when(repository.save(any(FeatureFlag.class))).thenAnswer(i -> i.getArgument(0));
        PayuSwitchService.GatewayStatus off = service.setEnabled(false);
        assertThat(off.enabled()).isFalse();
        assertThat(off.configured()).isFalse();
    }
}
