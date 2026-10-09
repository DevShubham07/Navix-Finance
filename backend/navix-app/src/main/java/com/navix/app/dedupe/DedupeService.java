package com.navix.app.dedupe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.app.dedupe.DedupeDtos.AadhaarDuplicate;
import com.navix.app.dedupe.DedupeDtos.BlocklistHit;
import com.navix.app.dedupe.DedupeDtos.DedupeView;
import com.navix.app.dedupe.DedupeDtos.RejectionBlock;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.util.Masking;
import com.navix.iam.domain.BlocklistType;
import com.navix.iam.entity.BlocklistEntry;
import com.navix.iam.repository.BlocklistEntryRepository;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationRejectionRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.service.ApplicationVerificationService;
import com.navix.loan.service.CustomerService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything the pop-up's Dedupe tab shows, in one staff read: the Aadhaar-duplicate flag, any active
 * blocklist entry matching this customer's identifiers, and the live rejection-register block.
 * Lives in navix-app because it needs navix-iam (blocklist) and navix-loan together.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DedupeService {

    private final LoanApplicationRepository applications;
    private final CustomerProfileRepository profiles;
    private final ApplicationVerificationRepository verifications;
    private final ApplicationRejectionRepository rejections;
    private final BlocklistEntryRepository blocklist;
    private final ObjectMapper objectMapper;
    private final CustomerService customerService;

    public DedupeView dedupe(Long customerId) {
        requireStaff();
        customerService.assertVisible(customerId); // DSA exclusion + the caller's customer scope
        List<LoanApplication> apps = applications.findByCustomerId(customerId).stream()
                .sorted(Comparator.comparing(LoanApplication::getId).reversed()).toList();
        if (apps.isEmpty()) {
            return new DedupeView(new AadhaarDuplicate(null, null, List.of(), null), List.of(), null);
        }
        LoanApplication latest = apps.get(0);
        CustomerProfile profile = realProfile(apps);
        return new DedupeView(aadhaar(apps), blocklistHits(latest, profile), rejection(profile));
    }

    /** Newest profile with a real name (the KYC one), else the newest — an abandoned shell must not hide the identifiers. */
    private CustomerProfile realProfile(List<LoanApplication> apps) {
        var byApp = profiles.findByApplicationIdIn(apps.stream().map(LoanApplication::getId).toList()).stream()
                .collect(java.util.stream.Collectors.toMap(CustomerProfile::getApplicationId, p -> p, (a, b) -> a));
        List<CustomerProfile> ordered = apps.stream().map(a -> byApp.get(a.getId())).filter(java.util.Objects::nonNull).toList();
        return ordered.stream().filter(p -> p.getFullName() != null && !p.getFullName().isBlank()).findFirst()
                .orElse(ordered.isEmpty() ? null : ordered.get(0));
    }

    private AadhaarDuplicate aadhaar(List<LoanApplication> apps) {
        for (LoanApplication app : apps) {
            ApplicationVerification row = verifications.findByApplicationIdAndCheckType(
                    app.getId(), ApplicationVerificationService.AADHAAR_DUPLICATE).orElse(null);
            if (row != null) {
                return new AadhaarDuplicate(row.getStatus(), app.getId(), otherCustomerIds(row.getDerived()),
                        row.getMessage());
            }
        }
        return new AadhaarDuplicate(null, null, List.of(), null);
    }

    private List<Long> otherCustomerIds(String derived) {
        List<Long> ids = new ArrayList<>();
        if (derived == null || derived.isBlank()) {
            return ids;
        }
        try {
            JsonNode arr = objectMapper.readTree(derived).path("otherCustomerIds");
            arr.forEach(n -> ids.add(n.asLong()));
        } catch (Exception e) {
            log.warn("unparseable AADHAAR_DUPLICATE derived"); // never log the payload
        }
        return ids;
    }

    private List<BlocklistHit> blocklistHits(LoanApplication app, CustomerProfile p) {
        List<BlocklistHit> hits = new ArrayList<>();
        if (p != null) {
            hit(hits, BlocklistType.PAN, p.getPan());
            hit(hits, BlocklistType.PHONE, p.getMobile());
            hit(hits, BlocklistType.AADHAAR_REF, p.getAadhaar());
            // ponytail: the entity doc calls AADHAAR_REF a "masked Aadhaar reference" and nothing writes one
            // yet, so the masked form is tried too; settle on one when an admin flow starts producing them.
            if (p.getAadhaar() != null && !p.getAadhaar().isBlank()) {
                hit(hits, BlocklistType.AADHAAR_REF, Masking.maskAadhaar(p.getAadhaar()));
            }
        }
        String disbursal = app.getDisbursalAccountNumber();
        String account = disbursal != null && !disbursal.isBlank() ? disbursal
                : p == null ? null : p.getSalaryAccountNumber();
        hit(hits, BlocklistType.BANK_ACCOUNT, account);
        return hits;
    }

    private void hit(List<BlocklistHit> hits, BlocklistType type, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        blocklist.findByTypeAndValue(type, value).filter(BlocklistEntry::isActive).ifPresent(e -> {
            String masked = Masking.maskAccount(value); // last four, X elsewhere — right for every type
            if (hits.stream().noneMatch(h -> h.type().equals(type.name()) && h.maskedValue().equals(masked))) {
                hits.add(new BlocklistHit(type.name(), masked, isAdmin() ? e.getReason() : null, e.getCreatedAt()));
            }
        });
    }

    private RejectionBlock rejection(CustomerProfile p) {
        if (p == null || p.getMobile() == null) {
            return null;
        }
        return rejections.findFirstByMobileAndBlockedUntilAfterOrderByBlockedUntilDesc(p.getMobile(), Instant.now())
                .map(r -> new RejectionBlock(r.getApplicationId(), r.getReasonCode(), isAdmin() ? r.getReasonDetail() : null,
                        r.getBlockedUntil()))
                .orElse(null);
    }

    /** Blocklist reasons and rejection detail are ADMIN-only elsewhere (BlocklistService, /rejections). */
    private static boolean isAdmin() {
        CurrentActor actor = ActorContext.get();
        return actor != null && "ADMIN".equals(actor.role());
    }

    /** Same semantics as CustomerController.requireStaff — DSA is an exclusion, not an absence. */
    private static void requireStaff() {
        CurrentActor actor = ActorContext.get();
        String role = actor == null ? null : actor.role();
        if (role == null || "BORROWER".equals(role) || "ANONYMOUS".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "Staff role required");
        }
        if ("DSA".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "DSAs cannot view customer data");
        }
    }
}
