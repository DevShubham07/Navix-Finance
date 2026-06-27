package com.navix.iam.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.iam.dto.PaymentDtos.PaymentSettingsResponse;
import com.navix.iam.dto.PaymentDtos.UpdatePaymentSettingsRequest;
import com.navix.iam.entity.PaymentSettings;
import com.navix.iam.repository.PaymentSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Reads and updates the singleton company payment block (the payee shown on the borrower repay
 * screen). Reads are open to any authenticated actor; writes are ADMIN-only.
 *
 * <p>The stored {@code qrObjectKey} / {@code accountInfoObjectKey} are S3 keys; on read they are
 * turned into short-lived presigned GET URLs via {@link DocumentStoragePort} (null when no asset is
 * stored). The keys themselves are never returned to clients.
 */
@Service
@RequiredArgsConstructor
public class PaymentSettingsService {

    private final PaymentSettingsRepository repository;
    private final DocumentStoragePort storage;

    /** Load the singleton settings (creating a default row if somehow absent). */
    @Transactional
    public PaymentSettingsResponse get() {
        return toResponse(loadOrCreate());
    }

    /** ADMIN-only update — applies non-null fields and returns the refreshed view. */
    @Transactional
    public PaymentSettingsResponse update(UpdatePaymentSettingsRequest req) {
        if (!"ADMIN".equals(ActorContext.get().role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "Only an admin can change payment settings.");
        }
        PaymentSettings settings = loadOrCreate();
        if (req.upiId() != null) {
            settings.setUpiId(req.upiId());
        }
        if (req.accountName() != null) {
            settings.setAccountName(req.accountName());
        }
        if (req.accountNumber() != null) {
            settings.setAccountNumber(req.accountNumber());
        }
        if (req.ifsc() != null) {
            settings.setIfsc(req.ifsc());
        }
        if (req.bankName() != null) {
            settings.setBankName(req.bankName());
        }
        if (req.qrObjectKey() != null) {
            settings.setQrObjectKey(req.qrObjectKey());
        }
        if (req.accountInfoObjectKey() != null) {
            settings.setAccountInfoObjectKey(req.accountInfoObjectKey());
        }
        return toResponse(repository.save(settings));
    }

    private PaymentSettings loadOrCreate() {
        return repository.findFirstByOrderByIdAsc()
                .orElseGet(() -> repository.save(new PaymentSettings()));
    }

    private PaymentSettingsResponse toResponse(PaymentSettings s) {
        return new PaymentSettingsResponse(
                s.getUpiId(),
                s.getAccountName(),
                s.getAccountNumber(),
                s.getIfsc(),
                s.getBankName(),
                presign(s.getQrObjectKey()),
                presign(s.getAccountInfoObjectKey()));
    }

    /** Short-lived presigned GET URL for a stored key, or null when there is no asset. */
    private String presign(String key) {
        return StringUtils.hasText(key) ? storage.presignDownload(key) : null;
    }
}
