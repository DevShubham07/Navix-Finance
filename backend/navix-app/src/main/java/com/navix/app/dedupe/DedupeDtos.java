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

    /** Mirrors the staff contract {@code BureauPhone}; kind MOBILE | MASKED | OTHER, source CRIF | EXPERIAN. */
    public record BureauPhone(String value, String normalized, String kind, java.time.LocalDate reportedDate,
                              String source, String context, boolean registered) {}

    public record BureauPhones(String provider, Long applicationId, Instant pulledAt, boolean identityMismatch,
                               List<BureauPhone> numbers) {}

    /** One of this customer's numbers that was searched; sources REGISTERED | BUREAU | REFERENCE. */
    public record OurNumber(String mobile, List<String> sources, String referenceName) {}

    /** kind BORROWER | BUREAU | REFERENCE | LEAD. Out-of-book rows carry inBook=false and no ids/names/status. */
    public record MobileMatch(String kind, String mobile, boolean inBook, Long customerId, Long applicationId,
                              String applicationStatus, String borrowerName, String contactName, String relation,
                              Long leadId, String leadName, String leadSource, String addedBy, Instant at) {}

    public record MobileMatchView(BureauPhones bureau, List<OurNumber> checked, List<MobileMatch> matches) {}
}
