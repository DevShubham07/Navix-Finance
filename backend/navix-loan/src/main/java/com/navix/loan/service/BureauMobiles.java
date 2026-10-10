package com.navix.loan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.common.util.Mobiles;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure extractor of the phone numbers a stored BUREAU raw response carries (CRIF phone variations,
 * Experian applicant + account-holder phones). Unknown shapes (e.g. Digitap CRIF) yield an empty list.
 * Field names mirror the staff contract {@code BureauPhone}.
 */
public final class BureauMobiles {

    private BureauMobiles() {}

    /** kind: MOBILE | MASKED | OTHER; source: CRIF | EXPERIAN. */
    public record Phone(String value, String normalized, String kind, LocalDate reportedDate, String source,
                        String context, boolean registered) {}

    private static final DateTimeFormatter CRIF_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter EXP_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    public static List<Phone> extract(JsonNode raw, Set<String> registered) {
        if (raw == null || raw.isMissingNode() || raw.isNull()) {
            return List.of();
        }
        Set<String> reg = registered == null ? Set.of() : registered;
        List<Phone> out = new ArrayList<>();
        JsonNode crif = firstWith(raw, "PERSONAL-INFO-VARIATION", "canonical.data.credit_report", "data",
                "canonical.data");
        if (crif != null) {
            crif(crif, reg, out);
        }
        JsonNode exp = firstWith(raw, "Current_Application", "data.jsonExperianReport", "data.credit_report",
                "result.result_json.INProfileResponse");
        if (exp != null) {
            experian(exp, reg, out);
        }
        return dedupe(out);
    }

    private static JsonNode firstWith(JsonNode raw, String marker, String... paths) {
        for (String p : paths) {
            JsonNode n = raw;
            for (String seg : p.split("\\.")) {
                n = n.path(seg);
            }
            if (n.isObject() && !n.path(marker).isMissingNode()) {
                return n;
            }
        }
        return null;
    }

    private static void crif(JsonNode root, Set<String> reg, List<Phone> out) {
        JsonNode variation = root.path("PERSONAL-INFO-VARIATION").path("PHONE-NUMBER-VARIATIONS").path("VARIATION");
        for (JsonNode v : items(variation)) {
            add(out, text(v.path("VALUE")), parse(text(v.path("REPORTED-DATE")), CRIF_DATE), "CRIF", null, reg);
        }
        add(out, text(root.path("REQUEST").path("PHONE-1")), null, "CRIF", "Our request", reg);
    }

    private static void experian(JsonNode root, Set<String> reg, List<Phone> out) {
        JsonNode applicant = root.path("Current_Application").path("Current_Application_Details")
                .path("Current_Applicant_Details");
        add(out, text(applicant.path("MobilePhoneNumber")), null, "EXPERIAN", "Application", reg);
        for (JsonNode acct : items(root.path("CAIS_Account").path("CAIS_Account_DETAILS"))) {
            LocalDate reported = parse(text(acct.path("Date_Reported")), EXP_DATE);
            String lender = text(acct.path("Subscriber_Name"));
            for (JsonNode ph : items(acct.path("CAIS_Holder_Phone_Details"))) {
                add(out, text(ph.path("Mobile_Telephone_Number")), reported, "EXPERIAN", lender, reg);
                add(out, text(ph.path("Telephone_Number")), reported, "EXPERIAN", lender, reg);
            }
        }
    }

    private static void add(List<Phone> out, String value, LocalDate date, String source, String context,
                            Set<String> reg) {
        if (value == null) {
            return;
        }
        if (value.matches(".*[Xx*].*")) {
            out.add(new Phone(value, null, "MASKED", date, source, context, false));
            return;
        }
        String digits = value.replaceAll("\\.0+$", "").replaceAll("\\D", "");
        if (digits.length() < 6) {
            return;
        }
        String n = Mobiles.normalize(digits);
        boolean mobile = n.matches("[6-9]\\d{9}");
        out.add(new Phone(value, mobile ? n : null, mobile ? "MOBILE" : "OTHER", date, source, context,
                mobile && reg.contains(n)));
    }

    /** One entry per number (normalised, else raw), keeping the newest reported date; first-seen order. */
    private static List<Phone> dedupe(List<Phone> in) {
        Map<String, Phone> m = new LinkedHashMap<>();
        for (Phone p : in) {
            String key = p.normalized() != null ? p.normalized() : p.kind() + ":" + p.value();
            Phone old = m.get(key);
            if (old == null) {
                m.put(key, p);
            } else if (p.reportedDate() != null
                    && (old.reportedDate() == null || p.reportedDate().isAfter(old.reportedDate()))) {
                m.put(key, p); // replaces in place, keeps first-seen order
            }
        }
        return new ArrayList<>(m.values());
    }

    private static List<JsonNode> items(JsonNode n) {
        List<JsonNode> l = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(l::add);
        } else if (n.isObject()) {
            l.add(n);
        }
        return l;
    }

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull() || n.isContainerNode()) {
            return null;
        }
        String s = n.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static LocalDate parse(String s, DateTimeFormatter f) {
        try {
            return s == null ? null : LocalDate.parse(s, f);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
