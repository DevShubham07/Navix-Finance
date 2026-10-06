package com.navix.verification.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Masks borrower identifiers in a provider payload before it is written to the application log.
 *
 * <p>The log line keeps the payload's <em>shape</em> (every key, status code, error code and
 * non-identifying value) so a failing integration can still be diagnosed from CloudWatch, while the
 * values that identify a person — PAN, Aadhaar, mobile, email, name, date of birth, address, account
 * number, consent OTP — become {@code [REDACTED]}. The untouched payload is still stored in the
 * access-controlled {@code provider_api_execution} row the log line names by {@code executionId}.
 *
 * <p>Two passes: values under an identifying key are masked whatever they look like; every other
 * string value is scanned for PAN / mobile / email / long digit runs, which catches identifiers a
 * provider echoes inside free text ("PAN ABCDE1234F not found"). A payload that is not JSON gets
 * only the second pass.
 */
final class ProviderLogRedactor {

    static final String MASK = "[REDACTED]";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Keys (lower-case, without {@code _}/{@code -}) whose value always identifies a person. */
    private static final Set<String> EXACT_KEYS = Set.of(
            "pan", "panno", "pannumber", "idnumber", "aadhaar", "aadhar", "aadhaarnumber", "uid", "uidnumber",
            "dob", "dateofbirth", "birthdate", "otp", "email", "emailid", "emailaddress",
            "mobile", "mobileno", "mobilenumber", "phone", "phoneno", "phonenumber",
            "accountno", "accountnumber", "bankaccountnumber", "address", "pincode");

    /** Suffixes / fragments that mark a person-identifying key ({@code first_name}, {@code customerMobile}). */
    private static final String[] SUFFIXES = {"name", "mobile", "phone", "email", "pan", "aadhaar", "dob", "otp"};
    private static final String[] FRAGMENTS = {"address", "accountnumber", "dateofbirth"};

    /** Names that end in "name" but carry no personal data and are useful when debugging. */
    private static final Set<String> NOT_PERSONAL = Set.of(
            "filename", "templatename", "productname", "servicename", "username", "hostname", "maskedname");

    private static final Pattern PAN = Pattern.compile("(?i)\\b[A-Z]{5}[0-9]{4}[A-Z]\\b");
    private static final Pattern MOBILE = Pattern.compile("(?<![0-9])(?:\\+?91[- ]?)?[6-9][0-9]{9}(?![0-9])");
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern LONG_DIGITS = Pattern.compile("(?<![0-9])[0-9]{12,18}(?![0-9])");

    private ProviderLogRedactor() {
    }

    static String redact(String payload) {
        if (payload == null || payload.isBlank()) {
            return payload;
        }
        try {
            JsonNode root = JSON.readTree(payload);
            if (root != null && root.isContainerNode()) {
                redactNode(root);
                return JSON.writeValueAsString(root);
            }
        } catch (Exception notJson) {
            // fall through to the free-text pass
        }
        return redactText(payload);
    }

    private static void redactNode(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> keys = new ArrayList<>();
            object.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                JsonNode value = object.get(key);
                if (value.isContainerNode() && isPersonalKey(key) && !value.isEmpty()) {
                    object.put(key, MASK); // e.g. "address": {"line1": …} — the whole thing is personal
                } else if (value.isContainerNode()) {
                    redactNode(value);
                } else if (isPersonalKey(key) && isIdentifyingValue(value)) {
                    object.put(key, MASK);
                } else if (value.isTextual()) {
                    object.put(key, redactText(value.asText()));
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode value = array.get(i);
                if (value.isContainerNode()) {
                    redactNode(value);
                } else if (value.isTextual()) {
                    array.set(i, TextNode.valueOf(redactText(value.asText())));
                }
            }
        }
    }

    static boolean isPersonalKey(String key) {
        String k = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (NOT_PERSONAL.contains(k)) {
            return false;
        }
        if (EXACT_KEYS.contains(k)) {
            return true;
        }
        for (String suffix : SUFFIXES) {
            if (k.endsWith(suffix)) {
                return true;
            }
        }
        for (String fragment : FRAGMENTS) {
            if (k.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** A flag ({@code "true"}, {@code false}) or an empty value identifies nobody — keep it. */
    private static boolean isIdentifyingValue(JsonNode value) {
        if (value.isNull() || value.isBoolean()) {
            return false;
        }
        String text = value.asText();
        return !text.isBlank() && !"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text);
    }

    static String redactText(String text) {
        String out = PAN.matcher(text).replaceAll(MASK);
        out = MOBILE.matcher(out).replaceAll(MASK);
        out = EMAIL.matcher(out).replaceAll(MASK);
        return LONG_DIGITS.matcher(out).replaceAll(MASK);
    }
}
