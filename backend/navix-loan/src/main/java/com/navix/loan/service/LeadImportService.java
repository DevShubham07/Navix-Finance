package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.dto.LeadDtos.ImportDuplicate;
import com.navix.loan.dto.LeadDtos.ImportExistingCustomer;
import com.navix.loan.dto.LeadDtos.ImportInFileDuplicate;
import com.navix.loan.dto.LeadDtos.ImportIssue;
import com.navix.loan.dto.LeadDtos.ImportPreview;
import com.navix.loan.dto.LeadDtos.ImportRequest;
import com.navix.loan.dto.LeadDtos.ImportResult;
import com.navix.loan.dto.LeadDtos.ImportRow;
import com.navix.loan.entity.Lead;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LeadRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin CSV lead import — bulk-loads an unattributed lead list from an uploaded CSV
 * (name / contact number / pan card / pincode / emailid). The uploaded leads have no
 * {@code owner_dsa_id} and land with {@code source = "OTHER"}, so they surface on
 * {@code /staff/admin/leads} and the telecalling queue, never the DSA register
 * ({@link DsaAdminService#leads} filters {@code ownerDsaId is not null}).
 *
 * <p>{@link #preview} classifies every row (issue / new / duplicate-of-an-existing-lead /
 * in-file-duplicate / already-a-customer) without writing anything; {@link #commit} re-runs the
 * SAME classification (the client's classification is never trusted) and then persists it.
 */
@Service
@RequiredArgsConstructor
public class LeadImportService {

    private static final int MAX_ROWS = 2000;
    private static final int MAX_ISSUES = 50;

    private static final Pattern MOBILE_PATTERN = Pattern.compile("^[6-9][0-9]{9}$");
    private static final Pattern PAN_PATTERN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    private static final Pattern PINCODE_PATTERN = Pattern.compile("^[1-9][0-9]{5}$");

    private final LeadRepository leadRepository;
    private final CustomerProfileRepository customerProfileRepository;

    @Transactional(readOnly = true)
    public ImportPreview preview(ImportRequest req) {
        requireAdmin();
        return classify(req).dto();
    }

    @Transactional
    public ImportResult commit(ImportRequest req) {
        requireAdmin();
        if (req.rows().size() > MAX_ROWS) {
            throw new BusinessException("CSV_TOO_LARGE",
                    "A CSV import is limited to " + MAX_ROWS + " rows (" + req.rows().size() + " given)");
        }

        Classified classified = classify(req);
        if (!classified.dto().issues().isEmpty()) {
            ImportIssue first = classified.dto().issues().get(0);
            throw new BusinessException("CSV_INVALID",
                    "Row " + first.row() + ": " + first.field() + " — " + first.message());
        }

        Long staffId = staffId();
        String sourceDetail = sourceDetailFor(req.fileName());
        List<Lead> toInsert = new ArrayList<>();
        for (NormalizedRow nr : classified.newRows()) {
            Lead l = new Lead();
            l.setName(nr.name());
            l.setMobile(nr.mobile());
            l.setPan(nr.pan());
            l.setPincode(nr.pincode());
            l.setEmail(nr.email());
            l.setSource("OTHER");
            l.setSourceDetail(sourceDetail);
            l.setCallStatus("NOT_CALLED");
            l.setCreatedByStaffId(staffId);
            toInsert.add(l);
        }
        List<Lead> inserted = toInsert.isEmpty() ? List.of() : leadRepository.saveAll(toInsert);

        int mergedCount = 0;
        if (req.merge()) {
            List<Lead> toSave = new ArrayList<>();
            for (MergeCandidate mc : classified.mergeCandidates()) {
                if (mc.fillableFields().isEmpty()) {
                    continue;
                }
                Lead existing = mc.existing();
                for (String field : mc.fillableFields()) {
                    if ("email".equals(field)) {
                        existing.setEmail(mc.row().email());
                    } else if ("pincode".equals(field)) {
                        existing.setPincode(mc.row().pincode());
                    } else if ("pan".equals(field)) {
                        existing.setPan(mc.row().pan());
                    }
                }
                toSave.add(existing);
            }
            if (!toSave.isEmpty()) {
                leadRepository.saveAll(toSave);
            }
            mergedCount = toSave.size();
        }

        int duplicateRows = classified.dto().duplicates().size() + classified.dto().inFileDuplicates().size();
        int skippedDuplicates = duplicateRows - mergedCount;
        int skippedCustomers = classified.dto().existingCustomers().size();
        List<Long> insertedIds = inserted.stream().map(Lead::getId).toList();

        return new ImportResult(inserted.size(), mergedCount, skippedDuplicates, skippedCustomers, insertedIds);
    }

    // ---- classification (shared by preview + commit) --------------------------------

    private Classified classify(ImportRequest req) {
        List<ImportIssue> issues = new ArrayList<>();
        List<NormalizedRow> normalized = new ArrayList<>();
        for (int i = 0; i < req.rows().size(); i++) {
            normalized.add(normalizeRow(i + 1, req.rows().get(i), issues));
        }

        if (!issues.isEmpty()) {
            List<ImportIssue> capped = issues.size() > MAX_ISSUES
                    ? new ArrayList<>(issues.subList(0, MAX_ISSUES))
                    : issues;
            ImportPreview dto = new ImportPreview(req.rows().size(), 0, List.of(), List.of(), List.of(), capped);
            return new Classified(dto, List.of(), List.of());
        }

        // In-file de-dup: mobile OR pan matches an earlier row -> later row dropped. Only index
        // pan when non-null, so blank PANs never collide.
        Map<String, Integer> mobileFirstRow = new HashMap<>();
        Map<String, Integer> panFirstRow = new HashMap<>();
        List<NormalizedRow> deduped = new ArrayList<>();
        List<ImportInFileDuplicate> inFileDuplicates = new ArrayList<>();
        for (NormalizedRow nr : normalized) {
            Integer byMobileRow = mobileFirstRow.get(nr.mobile());
            Integer byPanRow = nr.pan() == null ? null : panFirstRow.get(nr.pan());
            if (byMobileRow != null || byPanRow != null) {
                int dupOf;
                String matchedOn;
                if (byMobileRow != null && byPanRow != null && byMobileRow.equals(byPanRow)) {
                    dupOf = byMobileRow;
                    matchedOn = "MOBILE_AND_PAN";
                } else if (byMobileRow != null) {
                    dupOf = byMobileRow;
                    matchedOn = "MOBILE";
                } else {
                    dupOf = byPanRow;
                    matchedOn = "PAN";
                }
                inFileDuplicates.add(new ImportInFileDuplicate(nr.row(), dupOf, matchedOn));
                continue;
            }
            mobileFirstRow.put(nr.mobile(), nr.row());
            if (nr.pan() != null) {
                panFirstRow.put(nr.pan(), nr.row());
            }
            deduped.add(nr);
        }

        Set<String> mobiles = new LinkedHashSet<>();
        Set<String> pans = new LinkedHashSet<>();
        for (NormalizedRow nr : deduped) {
            mobiles.add(nr.mobile());
            if (nr.pan() != null) {
                pans.add(nr.pan());
            }
        }

        List<Lead> leadsByMobile = mobiles.isEmpty() ? List.of() : leadRepository.findByMobileIn(mobiles);
        List<Lead> leadsByPan = pans.isEmpty() ? List.of() : leadRepository.findByPanIn(pans);
        List<String> customerPans = pans.isEmpty() ? List.of() : customerProfileRepository.findPansIn(pans);
        List<String> customerMobiles = mobiles.isEmpty() ? List.of() : customerProfileRepository.findMobilesIn(mobiles);

        Map<String, Lead> leadByMobileMap = new HashMap<>();
        for (Lead l : leadsByMobile) {
            leadByMobileMap.putIfAbsent(l.getMobile(), l);
        }
        Map<String, Lead> leadByPanMap = new HashMap<>();
        for (Lead l : leadsByPan) {
            leadByPanMap.putIfAbsent(l.getPan(), l);
        }
        Set<String> customerPanSet = new HashSet<>(customerPans);
        Set<String> customerMobileSet = new HashSet<>(customerMobiles);

        List<ImportDuplicate> duplicates = new ArrayList<>();
        List<ImportExistingCustomer> existingCustomers = new ArrayList<>();
        List<NormalizedRow> freshRows = new ArrayList<>();
        List<MergeCandidate> mergeCandidates = new ArrayList<>();

        for (NormalizedRow nr : deduped) {
            boolean isCustomer = customerMobileSet.contains(nr.mobile())
                    || (nr.pan() != null && customerPanSet.contains(nr.pan()));
            if (isCustomer) {
                existingCustomers.add(new ImportExistingCustomer(nr.row(), nr.name(), nr.mobile(), maskPan(nr.pan())));
                continue;
            }

            Lead byMobile = leadByMobileMap.get(nr.mobile());
            Lead byPan = nr.pan() == null ? null : leadByPanMap.get(nr.pan());
            Lead existing = byMobile != null ? byMobile : byPan;
            if (existing != null) {
                String matchedOn;
                if (byMobile != null && byPan != null && byMobile.getId().equals(byPan.getId())) {
                    matchedOn = "MOBILE_AND_PAN";
                } else if (byMobile != null) {
                    matchedOn = "MOBILE";
                } else {
                    matchedOn = "PAN";
                }
                List<String> fillable = fillableFields(existing, nr, leadByPanMap);
                duplicates.add(new ImportDuplicate(nr.row(), nr.name(), nr.mobile(), nr.pan(), matchedOn,
                        existing.getId(), existing.getName(), existing.getMobile(), existing.getSource(), fillable));
                mergeCandidates.add(new MergeCandidate(existing, nr, fillable));
                continue;
            }

            freshRows.add(nr);
        }

        ImportPreview dto = new ImportPreview(
                req.rows().size(), freshRows.size(), duplicates, inFileDuplicates, existingCustomers, List.of());
        return new Classified(dto, freshRows, mergeCandidates);
    }

    private NormalizedRow normalizeRow(int rowNum, ImportRow r, List<ImportIssue> issues) {
        String name = r.name() == null ? "" : r.name().trim();
        if (name.isBlank()) {
            issues.add(new ImportIssue(rowNum, "name", "is required"));
        } else if (name.length() > 160) {
            issues.add(new ImportIssue(rowNum, "name", "must be at most 160 characters"));
        }

        String mobile = normalizeMobile(r.mobile());
        if (!MOBILE_PATTERN.matcher(mobile).matches()) {
            issues.add(new ImportIssue(rowNum, "mobile", "must be a valid 10-digit mobile number"));
        }

        String pan = normalizeBlank(r.pan());
        if (pan != null) {
            pan = pan.toUpperCase(Locale.ROOT);
            if (!PAN_PATTERN.matcher(pan).matches()) {
                issues.add(new ImportIssue(rowNum, "pan", "must be a valid PAN, e.g. ABCDE1234F"));
            }
        }

        String pincode = normalizeBlank(r.pincode());
        if (pincode != null && !PINCODE_PATTERN.matcher(pincode).matches()) {
            issues.add(new ImportIssue(rowNum, "pincode", "must be a valid 6-digit pincode"));
        }

        String email = normalizeBlank(r.email());
        if (email != null) {
            if (email.length() > 160) {
                issues.add(new ImportIssue(rowNum, "email", "must be at most 160 characters"));
            } else if (!isValidEmail(email)) {
                issues.add(new ImportIssue(rowNum, "email", "must be a valid email address"));
            }
        }

        return new NormalizedRow(rowNum, name, mobile, pan, pincode, email);
    }

    /**
     * The mobile rule — ONE definition, implemented identically here and in the frontend's
     * {@code mapLeadCsv}. Deliberately NOT {@code normalizeMobile} from elsewhere in the codebase:
     * that helper truncates with a length cap and would silently accept an 11-digit number.
     */
    private static String normalizeMobile(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("[^0-9]", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        }
        digits = digits.replaceFirst("^0+", "");
        return digits;
    }

    private static boolean isValidEmail(String email) {
        int at = email.indexOf('@');
        return at > 0 && email.indexOf('.', at) > at;
    }

    private static String normalizeBlank(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isBlank() ? null : t;
    }

    private static String maskPan(String pan) {
        return pan == null ? null : pan.substring(0, 2) + "XXXXX" + pan.substring(7);
    }

    /**
     * FILL BLANKS ONLY: email/pincode/pan on the existing lead. {@code pan} is fillable only when
     * the existing lead is unattributed ({@code ownerDsaId == null}) and no other lead in this
     * batch already holds that PAN — keeps {@code uq_lead_dsa_pan} (V55) untouched and never lets
     * two leads share a PAN.
     */
    private static List<String> fillableFields(Lead existing, NormalizedRow nr, Map<String, Lead> leadByPanMap) {
        List<String> fillable = new ArrayList<>();
        if (isBlank(existing.getEmail()) && nr.email() != null) {
            fillable.add("email");
        }
        if (isBlank(existing.getPincode()) && nr.pincode() != null) {
            fillable.add("pincode");
        }
        if (isBlank(existing.getPan()) && nr.pan() != null
                && existing.getOwnerDsaId() == null
                && leadByPanMap.get(nr.pan()) == null) {
            fillable.add("pan");
        }
        return fillable;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String sourceDetailFor(String fileName) {
        String detail = "CSV import: " + fileName;
        return detail.length() > 240 ? detail.substring(0, 240) : detail;
    }

    private void requireAdmin() {
        if (!"ADMIN".equals(ActorContext.get().role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "ADMIN required");
        }
    }

    private Long staffId() {
        CurrentActor actor = ActorContext.get();
        try {
            return Long.valueOf(actor.id());
        } catch (NumberFormatException e) {
            throw new BusinessException("FORBIDDEN_ROLE", "Staff identity required");
        }
    }

    // ---- internal shapes --------------------------------------------------------------

    private record NormalizedRow(int row, String name, String mobile, String pan, String pincode, String email) {
    }

    private record MergeCandidate(Lead existing, NormalizedRow row, List<String> fillableFields) {
    }

    private record Classified(ImportPreview dto, List<NormalizedRow> newRows, List<MergeCandidate> mergeCandidates) {
    }
}
