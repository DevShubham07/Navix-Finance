package com.navix.app.dedupe;

import java.time.Instant;
import java.util.List;

/** Wire shapes of the customer pop-up's Dedupe tab. Identifiers are masked to the last four. */
public final class DedupeDtos {

    private DedupeDtos() {}

    public record DedupeView(AadhaarDuplicate aadhaar, List<BlocklistHit> blocklistHits, RejectionBlock rejection) {}

    /** {@code status} is null when the customer has no AADHAAR_DUPLICATE row (clear). */
    public record AadhaarDuplicate(String status, Long applicationId, List<Long> otherCustomerIds, String message) {}

    public record BlocklistHit(String type, String maskedValue, String reason, Instant addedOn) {}

    /** The live rejection-register block on the customer's mobile; the view carries null when none. */
    public record RejectionBlock(Long applicationId, String reasonCode, String reasonDetail, Instant blockedUntil) {}
}
