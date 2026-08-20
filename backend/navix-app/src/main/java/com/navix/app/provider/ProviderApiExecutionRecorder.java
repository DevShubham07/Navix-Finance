package com.navix.app.provider;

import com.navix.verification.support.ProviderCall;
import com.navix.verification.support.ProviderCallLog;
import com.navix.verification.support.ProviderCallRecorder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Installs the real audit-trail writer into {@link ProviderCallLog} at startup, so every provider
 * call made anywhere in {@code navix-verification} lands in {@code provider_api_execution} and shows
 * up on the admin Provider API dashboard.
 *
 * <p>This class is the inversion point: {@code navix-app} owns the table but {@code navix-verification}
 * makes the calls, and the module dependency only runs one way.
 *
 * <p>It delegates rather than writing directly because {@link ProviderApiExecutionWriter#write} is
 * {@code REQUIRES_NEW} — a self-invocation would skip the proxy and quietly lose that guarantee.
 */
@Component
@RequiredArgsConstructor
public class ProviderApiExecutionRecorder implements ProviderCallRecorder {

    private final ProviderApiExecutionWriter writer;

    @PostConstruct
    void install() {
        ProviderCallLog.setRecorder(this);
    }

    @Override
    public Long record(ProviderCall call) {
        return writer.write(call);
    }
}
