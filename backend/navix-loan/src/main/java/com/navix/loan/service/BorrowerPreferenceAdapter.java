package com.navix.loan.service;

import com.navix.common.loan.BorrowerPreferenceDirectory;
import com.navix.common.notification.NotificationChannel;
import com.navix.loan.repository.BorrowerPreferencesRepository;
import java.util.EnumSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loan-module implementation of the {@link BorrowerPreferenceDirectory} port: maps a borrower's saved
 * {@code borrower_preferences} row to the set of channels they've opted OUT of, for the notification
 * dispatcher. No row → opted in to everything (empty set). IN_APP is never returned (it's the inbox).
 */
@Component
@RequiredArgsConstructor
public class BorrowerPreferenceAdapter implements BorrowerPreferenceDirectory {

    private final BorrowerPreferencesRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Set<NotificationChannel> optedOutChannels(Long applicantId) {
        if (applicantId == null) {
            return Set.of();
        }
        return repository.findByApplicantId(applicantId)
                .map(p -> {
                    Set<NotificationChannel> out = EnumSet.noneOf(NotificationChannel.class);
                    if (!p.isEmailOptIn()) {
                        out.add(NotificationChannel.EMAIL);
                    }
                    if (!p.isSmsOptIn()) {
                        out.add(NotificationChannel.SMS);
                    }
                    return (Set<NotificationChannel>) out;
                })
                .orElseGet(Set::of);
    }
}
