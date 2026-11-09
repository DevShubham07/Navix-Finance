package com.navix.loan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.security.ActorContext;
import com.navix.common.whatsapp.WhatsAppGateway;
import com.navix.common.whatsapp.WhatsAppGateway.TemplateInfo;
import com.navix.loan.dto.MessagingDtos.StoredTemplateRequest;
import com.navix.loan.dto.MessagingDtos.StoredTemplateView;
import com.navix.loan.dto.MessagingDtos.WhatsAppRefreshResult;
import com.navix.loan.entity.MessageTemplate;
import com.navix.loan.repository.MessageTemplateRepository;
import com.navix.loan.service.MessageCatalogue.Input;
import com.navix.loan.service.MessageCatalogue.Rule;
import com.navix.loan.service.MessageCatalogue.Template;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The ADMIN Templates library (V97). Sending is not here: {@link CustomerMessageService} turns a sendable
 * row into a catalogue {@link Template} ({@link #adapt}) so a stored template runs through the same admin,
 * flag, send-hours, opt-out, duplicate and ledger guards as the built-in ones.
 *
 * <p>Who owns status: SMS is kept by hand (STPL's DLT portal has no API). WhatsApp is read from SmartChat
 * and cannot be set here; {@code insert_template_api} is broken, so a template is filed in the portal and
 * registered here. Email needs no approval, so saved means Active.
 */
@Service
@RequiredArgsConstructor
public class MessageTemplateService {

    public static final String KEY_PREFIX = "tpl:";
    public static final String EMAIL = "EMAIL";

    private static final Set<String> STATUSES = Set.of(
            MessageTemplate.DRAFT, MessageTemplate.SUBMITTED, MessageTemplate.ACTIVE, MessageTemplate.REJECTED);
    private static final Set<String> PURPOSES = Set.of("COLLECTIONS", "REPAYMENT", "DISCOUNTS", "LOAN_UPDATES", "KYC",
            "REFERENCES", "OTHER");
    private static final Set<String> CATEGORIES = Set.of(MessageCatalogue.SERVICE, MessageCatalogue.MARKETING);
    private static final Set<String> OVERDUE_VARIABLES = Set.of("daysOverdue", "dailyCharge");
    private static final Set<String> LOAN_VARIABLES = Set.of("amount", "loanId", "dueDate", "dueDatePlus5");
    private static final Set<String> OTHER_VARIABLES = Set.of("name", "salaryDate");

    private static final Pattern TYPED_VARIABLE = Pattern.compile("in:(text|date):[^:]{1,40}");
    private static final Pattern DLT_ID = Pattern.compile("\\d{19}");
    private static final Pattern WHATSAPP_NAME = Pattern.compile("[a-z0-9_]{1,128}");
    private static final String DLT_VAR = "{#var#}";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{#var#\\}|\\{\\{(\\d+)\\}\\}");
    private static final int WHATSAPP_MAX_BODY = 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MessageTemplateRepository repository;
    private final FeatureFlagService featureFlags;
    private final WhatsAppGateway whatsApp;

    // ---------------------------------------------------------------- reads

    public List<StoredTemplateView> list(String channel) {
        requireAdmin();
        return repository.findByChannelOrderByIdDesc(channel).stream().map(MessageTemplateService::view).toList();
    }

    /** Every template that can be sent now, as catalogue templates for the send dialog. */
    public List<Template> sendable() {
        return repository.findByStatusAndSystemManagedFalse(MessageTemplate.ACTIVE).stream()
                .filter(t -> unsendableReason(t) == null)
                .map(MessageTemplateService::adapt)
                .toList();
    }

    public static boolean isStoredKey(String key) {
        return key != null && key.startsWith(KEY_PREFIX);
    }

    /** The row behind {@code tpl:<id>}, or a refusal saying why it cannot be sent. */
    public MessageTemplate requireSendable(String key) {
        MessageTemplate row = parseId(key).flatMap(repository::findById)
                .orElseThrow(() -> new BusinessException("INVALID_TEMPLATE", "Unknown message template"));
        String why = unsendableReason(row);
        if (why != null) {
            throw new BusinessException("TEMPLATE_NOT_SENDABLE", why);
        }
        return row;
    }

    static String unsendableReason(MessageTemplate t) {
        if (t.isSystemManaged()) {
            return "Sent automatically by its own flow, never by hand.";
        }
        if (t.getBody() == null || t.getBody().isBlank()) {
            return "The registered wording has not been filled in.";
        }
        if (!MessageTemplate.ACTIVE.equals(t.getStatus())) {
            return "Only an Active template can be sent.";
        }
        if (!EMAIL.equals(t.getChannel()) && (t.getProviderId() == null || t.getProviderId().isBlank())) {
            return MessageCatalogue.SMS.equals(t.getChannel()) ? "No DLT template id." : "Not found on SmartChat yet.";
        }
        return null;
    }

    // ---------------------------------------------------------------- send support

    /** The row as a catalogue template; inputs are {@code v1..vn}, one per typed placeholder. */
    static Template adapt(MessageTemplate t) {
        List<String> variables = variablesOf(t);
        List<String> params = new ArrayList<>();
        List<Input> inputs = new ArrayList<>();
        for (int i = 0; i < variables.size(); i++) {
            String v = variables.get(i);
            if (v.startsWith("in:")) {
                String[] parts = v.split(":", 3);
                String name = "v" + (i + 1);
                params.add("in:" + name);
                inputs.add(new Input(name, parts[2], "date".equals(parts[1]) ? "DATE" : "TEXT"));
            } else {
                params.add(v);
            }
        }
        String provider = EMAIL.equals(t.getChannel()) ? t.getName() : t.getProviderId();
        // A typed value is usually about one customer, so it is not offered on a multi-select.
        return new Template(KEY_PREFIX + t.getId(), t.getName(), t.getCategory(), provider, null,
                List.of(t.getChannel()), params, null, null, inputs, !inputs.isEmpty(), t.getConfirmText(),
                Rule.valueOf(t.getRule()), false);
    }

    /** Fill placeholders: {@code {#var#}} in order (SMS), {@code {{n}}} by number (WhatsApp, email). */
    static String render(String text, List<String> values) {
        if (text == null) {
            return null;
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        int next = 0;
        while (m.find()) {
            int index = m.group(1) == null ? next++ : Integer.parseInt(m.group(1)) - 1;
            m.appendReplacement(out, Matcher.quoteReplacement(values.get(index)));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ---------------------------------------------------------------- writes

    public StoredTemplateView create(StoredTemplateRequest req) {
        requireEnabledAdmin();
        String channel = req.channel().trim().toUpperCase(Locale.ROOT);
        if (!Set.of(MessageCatalogue.SMS, MessageCatalogue.WHATSAPP, EMAIL).contains(channel)) {
            throw new BusinessException("INVALID_CHANNEL", "Channel must be SMS, WHATSAPP or EMAIL");
        }
        String name = requireName(channel, req.name());
        if (repository.existsByChannelAndName(channel, name)) {
            throw new BusinessException("TEMPLATE_EXISTS", "A template with this name already exists");
        }
        MessageTemplate t = new MessageTemplate();
        t.setChannel(channel);
        t.setName(name);
        t.setStatus(EMAIL.equals(channel) ? MessageTemplate.ACTIVE : MessageTemplate.DRAFT);
        t.setCreatedByName(ActorContext.get().name());
        t.setCreatedByStaffId(actorStaffId());
        apply(t, req);
        return view(repository.save(t));
    }

    public StoredTemplateView update(Long id, StoredTemplateRequest req) {
        requireEnabledAdmin();
        MessageTemplate t = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Template", String.valueOf(id)));
        if (MessageCatalogue.WHATSAPP.equals(t.getChannel()) && t.isSystemManaged()) {
            // A listed SmartChat template: only its status, and wording not yet on file, are kept here.
            String missing = t.getBody() == null ? trimToNull(req.body()) : null;
            if (missing != null) {
                checkWhatsAppBody(missing);
                t.setBody(missing);
            }
            applyPurpose(t, req);
            applyWhatsAppStatus(t, req);
            return view(repository.save(t));
        }
        boolean wordingChanged = !Objects.equals(trimToNull(req.body()), t.getBody())
                || !jsonList(req.variables()).equals(t.getVariables());
        // The category decides who may opt out and the rule who may receive it, so both are locked with the wording.
        boolean termsChanged = !req.category().trim().toUpperCase(Locale.ROOT).equals(t.getCategory())
                || !effectiveRule(req.rule(), req.variables() == null ? List.of() : req.variables()).equals(t.getRule());
        if (MessageCatalogue.WHATSAPP.equals(t.getChannel()) && (wordingChanged || termsChanged)
                && !Set.of(MessageTemplate.DRAFT, MessageTemplate.REJECTED).contains(t.getStatus())) {
            throw new BusinessException("TEMPLATE_LOCKED",
                    "This template is with Meta. To change it, add a new one under a new name.");
        }
        if (MessageTemplate.ACTIVE.equals(t.getStatus()) && !EMAIL.equals(t.getChannel())
                && t.getBody() != null && !t.getBody().isBlank() && wordingChanged) {
            throw new BusinessException("TEMPLATE_LOCKED",
                    "An Active template's wording matches its registration. To change it, add a new one.");
        }
        apply(t, req);
        return view(repository.save(t));
    }

    private void apply(MessageTemplate t, StoredTemplateRequest req) {
        String category = req.category().trim().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(category)) {
            throw new BusinessException("INVALID_CATEGORY", "Category must be SERVICE or MARKETING");
        }
        String body = trimToNull(req.body());
        String subject = trimToNull(req.subject());
        List<String> variables = req.variables() == null ? List.of() : req.variables();
        variables.forEach(MessageTemplateService::checkVariable);
        checkPlaceholders(t.getChannel(), subject, body, variables);
        switch (t.getChannel()) {
            case MessageCatalogue.SMS -> applySmsStatus(t, req, body);
            case MessageCatalogue.WHATSAPP -> {
                checkWhatsAppBody(body);
                applyWhatsAppStatus(t, req);
            }
            default -> {
                if (body == null || subject == null || subject.length() > 200
                        || subject.chars().anyMatch(Character::isISOControl)) {
                    throw new BusinessException("INVALID_TEMPLATE",
                            "Email needs a one-line subject (up to 200 characters) and a body");
                }
            }
        }
        applyPurpose(t, req);
        t.setCategory(category);
        t.setSubject(subject);
        t.setBody(body);
        t.setVariables(jsonList(variables));
        t.setRule(effectiveRule(req.rule(), variables));
    }

    private static void applyPurpose(MessageTemplate t, StoredTemplateRequest req) {
        String purpose = req.purpose() == null || req.purpose().isBlank()
                ? t.getPurpose() : req.purpose().trim().toUpperCase(Locale.ROOT);
        if (purpose == null || !PURPOSES.contains(purpose)) {
            throw new BusinessException("INVALID_CATEGORY", "Choose a category for this template");
        }
        t.setPurpose(purpose);
        t.setLabel(trimToNull(req.label()));
    }

    /** Removes it from this page only; SmartChat and DLT keep their own copy. */
    public void delete(Long id) {
        requireEnabledAdmin();
        MessageTemplate t = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Template", String.valueOf(id)));
        if (t.isSystemManaged()) {
            throw new BusinessException("TEMPLATE_PROTECTED", "This template is sent by the system and cannot be deleted");
        }
        repository.delete(t);
    }

    /** Kept by hand when SmartChat's list cannot be read; Refresh overwrites it when it can. */
    private void applyWhatsAppStatus(MessageTemplate t, StoredTemplateRequest req) {
        String status = req.status() == null || req.status().isBlank() ? t.getStatus() : req.status().trim();
        if (!STATUSES.contains(status)) {
            throw new BusinessException("INVALID_STATUS", "Unknown template status");
        }
        if (MessageTemplate.DRAFT.equals(status)
                && Set.of(MessageTemplate.ACTIVE, MessageTemplate.SUBMITTED).contains(t.getStatus())) {
            throw new BusinessException("INVALID_STATUS", "A template already with SmartChat cannot go back to Draft");
        }
        String stored = trimToNull(req.providerId());
        if (stored == null && MessageTemplate.ACTIVE.equals(status)) {
            stored = t.getName();
        }
        // Its own name, or the name SmartChat stored it under: never another template's.
        if (stored != null && !stored.equals(t.getName())
                && !stored.matches(Pattern.quote(t.getName()) + "_\\d+")) {
            throw new BusinessException("INVALID_STORED_NAME", "Use this template's own name as stored on SmartChat");
        }
        if (stored != null && !stored.equals(t.getProviderId())
                && repository.existsByChannelAndProviderId(t.getChannel(), stored)) {
            throw new BusinessException("STORED_NAME_IN_USE", "Another template already has this stored name");
        }
        t.setStatus(status);
        t.setProviderId(stored);
        t.setRejectionReason(MessageTemplate.REJECTED.equals(status) ? truncate(trimToNull(req.remarks()), 1000) : null);
    }

    private void applySmsStatus(MessageTemplate t, StoredTemplateRequest req, String body) {
        if (body != null && !StandardCharsets.US_ASCII.newEncoder().canEncode(body)) {
            throw new BusinessException("INVALID_TEMPLATE",
                    "SMS wording must be plain English characters: write Rs. rather than the rupee sign");
        }
        String status = req.status() == null || req.status().isBlank() ? t.getStatus() : req.status().trim();
        if (!STATUSES.contains(status)) {
            throw new BusinessException("INVALID_STATUS", "Unknown template status");
        }
        String dltId = trimToNull(req.providerId());
        if (dltId != null && !DLT_ID.matcher(dltId).matches()) {
            throw new BusinessException("INVALID_DLT_ID", "A DLT template id is 19 digits");
        }
        if (dltId != null && !dltId.equals(t.getProviderId())
                && repository.existsByChannelAndProviderId(t.getChannel(), dltId)) {
            throw new BusinessException("DLT_ID_IN_USE", "Another template already has this DLT id");
        }
        if (MessageTemplate.ACTIVE.equals(status) && (dltId == null || body == null)) {
            throw new BusinessException("INVALID_STATUS", "An Active SMS template needs its wording and DLT id");
        }
        t.setStatus(status);
        t.setProviderId(dltId);
        t.setRejectionReason(truncate(trimToNull(req.remarks()), 1000));
    }

    /** The portal's own rules, so a template that will bounce there is caught here. */
    private static void checkWhatsAppBody(String body) {
        if (body == null) {
            throw new BusinessException("INVALID_TEMPLATE", "Enter the template wording");
        }
        String problem = body.length() > WHATSAPP_MAX_BODY ? "is longer than 1024 characters"
                : body.contains("\"") ? "contains a double quote"
                : body.toLowerCase(Locale.ROOT).contains("whatsapp") ? "contains the word WhatsApp"
                : body.contains("\n\n\n") ? "has more than two line breaks in a row"
                : body.startsWith("{{") || body.endsWith("}}") ? "starts or ends with a variable"
                : null;
        if (problem != null) {
            throw new BusinessException("INVALID_TEMPLATE", "The wording " + problem + ", which the portal refuses");
        }
    }

    private static void checkPlaceholders(String channel, String subject, String body, List<String> variables) {
        if (body == null) {
            if (!variables.isEmpty()) {
                throw new BusinessException("INVALID_TEMPLATE", "Variables need wording to go in");
            }
            return;
        }
        String text = (subject == null ? "" : subject + "\n") + body;
        int count;
        if (MessageCatalogue.SMS.equals(channel)) {
            if (text.contains("{{")) {
                throw new BusinessException("INVALID_TEMPLATE", "SMS wording uses {#var#}, not {{1}}");
            }
            count = text.split(Pattern.quote(DLT_VAR), -1).length - 1;
        } else {
            if (text.contains(DLT_VAR)) {
                throw new BusinessException("INVALID_TEMPLATE", "This wording uses {{1}}, {{2}}, not {#var#}");
            }
            Set<Integer> numbers = new TreeSet<>();
            Matcher m = PLACEHOLDER.matcher(text);
            while (m.find()) {
                numbers.add(Integer.parseInt(m.group(1)));
            }
            count = numbers.size();
            int expected = 1;
            for (int n : numbers) {
                if (n != expected++) {
                    throw new BusinessException("INVALID_TEMPLATE", "Variables must run {{1}}, {{2}}... with no gaps");
                }
            }
        }
        if (count != variables.size()) {
            throw new BusinessException("INVALID_TEMPLATE",
                    "The wording has " + count + " variable(s) but " + variables.size() + " were chosen");
        }
    }

    private static void checkVariable(String v) {
        if (v == null) {
            throw new BusinessException("INVALID_TEMPLATE", "A variable is empty");
        }
        boolean known = OVERDUE_VARIABLES.contains(v) || LOAN_VARIABLES.contains(v) || OTHER_VARIABLES.contains(v);
        if (!known && !TYPED_VARIABLE.matcher(v).matches()) {
            throw new BusinessException("INVALID_TEMPLATE", "Unknown variable: " + v);
        }
    }

    /** At least as strict as the variables need: a loan figure cannot be sent to someone with no loan. */
    private static String effectiveRule(String requested, List<String> variables) {
        if ("OVERDUE".equals(requested) || variables.stream().anyMatch(OVERDUE_VARIABLES::contains)) {
            return Rule.OVERDUE.name();
        }
        if ("ANY".equals(requested) && variables.stream().noneMatch(LOAN_VARIABLES::contains)) {
            return Rule.ANY.name();
        }
        return Rule.LIVE.name();
    }

    private static String requireName(String channel, String raw) {
        String name = raw.trim();
        if (name.isEmpty() || name.length() > 128) {
            throw new BusinessException("INVALID_TEMPLATE", "A name is required (up to 128 characters)");
        }
        if (MessageCatalogue.WHATSAPP.equals(channel) && !WHATSAPP_NAME.matcher(name).matches()) {
            throw new BusinessException("INVALID_TEMPLATE", "WhatsApp names are lowercase letters, digits and underscores");
        }
        return name;
    }

    // ---------------------------------------------------------------- SmartChat status

    /** Read every template from SmartChat and update the WhatsApp rows. Changes nothing on the provider. */
    public WhatsAppRefreshResult refreshWhatsApp() {
        requireEnabledAdmin();
        List<TemplateInfo> remote;
        try {
            remote = whatsApp.listTemplates();
        } catch (RuntimeException e) {
            throw new BusinessException("WHATSAPP_UNAVAILABLE", "Could not read templates from SmartChat");
        }
        int updated = 0;
        List<String> notFound = new ArrayList<>();
        for (MessageTemplate t : repository.findByChannelOrderByIdDesc(MessageCatalogue.WHATSAPP)) {
            TemplateInfo found = match(t.getName(), remote);
            if (found == null) {
                notFound.add(t.getName());
                continue;
            }
            t.setProviderId(found.name());
            t.setStatus(mapStatus(found.status()));
            t.setRejectionReason(MessageTemplate.REJECTED.equals(t.getStatus())
                    ? truncate(found.rejectedReason(), 1000) : null);
            repository.save(t);
            updated++;
        }
        return new WhatsAppRefreshResult(updated, notFound);
    }

    /** The exact name, else the one stored with a numeric suffix; none if the suffix is ambiguous. */
    static TemplateInfo match(String name, List<TemplateInfo> remote) {
        TemplateInfo exact = remote.stream().filter(r -> name.equals(r.name())).findFirst().orElse(null);
        if (exact != null) {
            return exact;
        }
        Pattern suffixed = Pattern.compile(Pattern.quote(name) + "_\\d+");
        List<TemplateInfo> candidates = remote.stream()
                .filter(r -> r.name() != null && suffixed.matcher(r.name()).matches()).toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static String mapStatus(String remote) {
        String s = remote == null ? "" : remote.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "APPROVED" -> MessageTemplate.ACTIVE;
            case "REJECTED" -> MessageTemplate.REJECTED;
            default -> MessageTemplate.SUBMITTED;
        };
    }

    // ---------------------------------------------------------------- helpers

    private static StoredTemplateView view(MessageTemplate t) {
        return new StoredTemplateView(t.getId(), KEY_PREFIX + t.getId(), t.getChannel(), t.getName(), t.getSubject(),
                t.getBody(), t.getCategory(), t.getStatus(), t.getProviderId(), t.getRejectionReason(), variablesOf(t),
                t.getRule(), t.getConfirmText(), t.isSystemManaged(), unsendableReason(t), t.getCreatedByName(),
                t.getCreatedAt(), t.getUpdatedAt(), t.getPurpose(), t.getLabel());
    }

    private static List<String> variablesOf(MessageTemplate t) {
        try {
            return JSON.readValue(t.getVariables(),
                    JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Template " + t.getId() + " has unreadable variables", e);
        }
    }

    private static String jsonList(List<String> variables) {
        try {
            return JSON.writeValueAsString(variables == null ? List.of() : variables);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Optional<Long> parseId(String key) {
        try {
            return Optional.of(Long.valueOf(key.substring(KEY_PREFIX.length())));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private void requireEnabledAdmin() {
        requireAdmin();
        if (!featureFlags.isEnabled(CustomerMessageService.FLAG, false)) {
            throw new BusinessException("FEATURE_DISABLED", "Customer messaging is switched off");
        }
    }

    /** The real role, never the working role; DSA and every other staff role fail here. */
    private static void requireAdmin() {
        if (!"ADMIN".equals(ActorContext.get().role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "Only an admin can manage message templates");
        }
    }

    private static Long actorStaffId() {
        try {
            return Long.valueOf(ActorContext.get().id());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
