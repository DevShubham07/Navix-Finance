package com.navix.app.dedupe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.app.dedupe.DedupeDtos.BureauPhone;
import com.navix.app.dedupe.DedupeDtos.BureauPhones;
import com.navix.app.dedupe.DedupeDtos.MobileMatch;
import com.navix.app.dedupe.DedupeDtos.MobileMatchView;
import com.navix.app.dedupe.DedupeDtos.OurNumber;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.util.Mobiles;
import com.navix.loan.entity.ApplicationReference;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.Lead;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationReferenceRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.ApplicationVerificationRepository.BureauRawRow;
import com.navix.loan.repository.BureauPhoneRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LeadRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.service.BureauMobiles;
import com.navix.loan.service.CustomerService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Mobile used in N other cases": this customer's numbers (registered + bureau-reported + their own
 * references) matched against other customers' registered mobiles, other customers' bureau numbers,
 * every reference on every application, and (TELECALLER/ADMIN only) leads. Bounded query count
 * (no per-row queries); numbers are never logged. Out-of-book matches are counted but redacted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MobileMatchService {

    private final LoanApplicationRepository applications;
    private final CustomerProfileRepository profiles;
    private final ApplicationVerificationRepository verifications;
    private final ApplicationReferenceRepository references;
    private final BureauPhoneRepository bureauPhones;
    private final LeadRepository leads;
    private final StaffDirectory staffDirectory;
    private final ObjectMapper objectMapper;
    private final CustomerService customerService;

    public MobileMatchView matches(Long customerId) {
        requireStaff();
        customerService.assertVisible(customerId);

        List<LoanApplication> apps = applications.findByCustomerId(customerId).stream()
                .sorted(Comparator.comparing(LoanApplication::getId).reversed()).toList();
        if (apps.isEmpty()) {
            return new MobileMatchView(null, List.of(), List.of());
        }
        List<Long> appIds = apps.stream().map(LoanApplication::getId).toList();

        Set<String> registered = new LinkedHashSet<>();
        for (CustomerProfile p : profiles.findByApplicationIdIn(appIds)) {
            String n = Mobiles.normalize(p.getMobile());
            if (n != null) {
                registered.add(n);
            }
        }

        BureauPhones bureau = bureauBlock(appIds, registered);
        Map<String, Set<String>> sources = new LinkedHashMap<>();
        Map<String, String> refNames = new LinkedHashMap<>();
        registered.forEach(m -> sources.computeIfAbsent(m, k -> new LinkedHashSet<>()).add("REGISTERED"));
        if (bureau != null && !bureau.identityMismatch()) {
            bureau.numbers().stream().filter(b -> "MOBILE".equals(b.kind()))
                    .forEach(b -> sources.computeIfAbsent(b.normalized(), k -> new LinkedHashSet<>()).add("BUREAU"));
        }
        for (ApplicationReference r : references.findByCustomerId(customerId)) {
            String n = Mobiles.normalize(r.getMobile());
            if (n != null) {
                sources.computeIfAbsent(n, k -> new LinkedHashSet<>()).add("REFERENCE");
                refNames.putIfAbsent(n, r.getFullName());
            }
        }
        List<OurNumber> checked = sources.entrySet().stream()
                .map(e -> new OurNumber(e.getKey(), List.copyOf(e.getValue()), refNames.get(e.getKey())))
                .toList();
        if (checked.isEmpty()) {
            return new MobileMatchView(bureau, checked, List.of());
        }
        Set<String> search = sources.keySet();

        List<MobileMatch> raw = new ArrayList<>();
        Set<String> borrowerPairs = new HashSet<>();
        borrowerMatches(search, customerId, raw, borrowerPairs);
        bureauMatches(search, customerId, raw, borrowerPairs);
        referenceMatches(search, customerId, raw);
        Set<Long> ids = raw.stream().map(MobileMatch::customerId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> visible = customerService.visibleAmong(ids);
        List<MobileMatch> out = new ArrayList<>();
        for (MobileMatch m : raw) {
            out.add(visible.contains(m.customerId()) ? m : redact(m));
        }
        if (mayReadLeads()) {
            leadMatches(search, registered, out);
        }
        return new MobileMatchView(bureau, checked, out);
    }

    /** Newest application whose BUREAU raw parses (reborrows carry none themselves). */
    private BureauPhones bureauBlock(List<Long> appIdsNewestFirst, Set<String> registered) {
        Map<Long, BureauRawRow> byApp = verifications.findBureauRaw(appIdsNewestFirst).stream()
                .collect(Collectors.toMap(BureauRawRow::getApplicationId, Function.identity(), (a, b) -> a));
        for (Long appId : appIdsNewestFirst) {
            BureauRawRow row = byApp.get(appId);
            if (row == null) {
                continue;
            }
            try {
                JsonNode rawJson = objectMapper.readTree(row.getRawResponse());
                boolean mismatch = false;
                if (row.getDerived() != null) {
                    JsonNode m = objectMapper.readTree(row.getDerived()).path("identityMismatch");
                    mismatch = !m.isMissingNode() && !m.isNull();
                }
                List<BureauPhone> nums = BureauMobiles.extract(rawJson, registered).stream()
                        .map(p -> new BureauPhone(p.value(), p.normalized(), p.kind(), p.reportedDate(), p.source(),
                                p.context(), p.registered()))
                        .toList();
                return new BureauPhones(row.getProvider(), appId, row.getUpdatedAt(), mismatch, nums);
            } catch (Exception e) {
                log.warn("unparseable bureau raw application={}", appId); // never log the payload
            }
        }
        return null;
    }

    private void borrowerMatches(Set<String> search, Long customerId, List<MobileMatch> out, Set<String> pairs) {
        Set<Long> seen = new HashSet<>();
        for (Object[] r : profiles.findOtherBorrowersByMobileIn(search, customerId)) {
            Long cid = (Long) r[1];
            if (!seen.add(cid)) {
                continue; // one per other customer, newest application first
            }
            String bm = Mobiles.normalize((String) r[0]);
            pairs.add(cid + ":" + bm);
            out.add(new MobileMatch("BORROWER", bm, true, cid, (Long) r[2],
                    String.valueOf(r[3]), (String) r[4], null, null, null, null, null, null, (Instant) r[5]));
        }
    }

    private void bureauMatches(Set<String> search, Long customerId, List<MobileMatch> out, Set<String> borrowerPairs) {
        List<com.navix.loan.entity.BureauPhone> rows = bureauPhones.findOtherCustomersByMobileIn(search, customerId);
        if (rows.isEmpty()) {
            return;
        }
        List<Long> appIds = rows.stream().map(com.navix.loan.entity.BureauPhone::getApplicationId).distinct().toList();
        Map<Long, String> names = profiles.findByApplicationIdIn(appIds).stream()
                .filter(p -> p.getFullName() != null && !p.getFullName().isBlank())
                .collect(Collectors.toMap(CustomerProfile::getApplicationId, CustomerProfile::getFullName, (a, b) -> a));
        Map<Long, LoanApplication> apps = applications.findAllById(appIds).stream()
                .collect(Collectors.toMap(LoanApplication::getId, Function.identity()));
        Set<String> seen = new HashSet<>(borrowerPairs); // already counted as a BORROWER match
        for (com.navix.loan.entity.BureauPhone b : rows) { // newest application first
            if (!seen.add(b.getCustomerId() + ":" + b.getMobile())) {
                continue;
            }
            LoanApplication a = apps.get(b.getApplicationId());
            out.add(new MobileMatch("BUREAU", b.getMobile(), true, b.getCustomerId(), b.getApplicationId(),
                    a == null ? null : String.valueOf(a.getStatus()), names.get(b.getApplicationId()), null, null,
                    null, null, null, null, b.getPulledAt()));
        }
    }

    private void referenceMatches(Set<String> search, Long customerId, List<MobileMatch> out) {
        Set<String> seen = new HashSet<>();
        for (Object[] r : references.findOtherReferencesByMobileIn(search, customerId)) {
            ApplicationReference ref = (ApplicationReference) r[0];
            String mobile = Mobiles.normalize(ref.getMobile());
            if (!seen.add(ref.getCustomerId() + ":" + mobile)) {
                continue;
            }
            out.add(new MobileMatch("REFERENCE", mobile, true, ref.getCustomerId(), ref.getApplicationId(),
                    String.valueOf(r[2]), (String) r[1], ref.getFullName(), ref.getRelation(), null, null, null,
                    null, ref.getCreatedAt()));
        }
    }

    private void leadMatches(Set<String> search, Set<String> registered, List<MobileMatch> out) {
        List<Lead> found = leads.findByMobileIn(search).stream()
                .filter(l -> l.getOwnerDsaId() == null && !registered.contains(l.getMobile()))
                .toList();
        if (found.isEmpty()) {
            return;
        }
        Map<Long, String> staff = staffDirectory.namesFor(
                found.stream().map(Lead::getCreatedByStaffId).collect(Collectors.toSet()));
        for (Lead l : found) {
            out.add(new MobileMatch("LEAD", l.getMobile(), true, null, null, null, null, null, null, l.getId(),
                    l.getName(), l.getSource(), staff.get(l.getCreatedByStaffId()), l.getCreatedAt()));
        }
    }

    private static MobileMatch redact(MobileMatch m) {
        return new MobileMatch(m.kind(), m.mobile(), false, null, null, null, null, null, null, null, null, null,
                null, null);
    }

    /** Authorisation stays on the REAL role (effectiveRole only scopes lists). */
    private static boolean mayReadLeads() {
        CurrentActor actor = ActorContext.get();
        return actor != null && ("TELECALLER".equals(actor.role()) || "ADMIN".equals(actor.role()));
    }

    /** Same semantics as DedupeService.requireStaff — DSA is an exclusion, not an absence. */
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
