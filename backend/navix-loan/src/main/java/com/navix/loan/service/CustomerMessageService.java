package com.navix.loan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.notification.MailPort;
import com.navix.common.security.ActorContext;
import com.navix.common.sms.SmsGateway;
import com.navix.common.util.Masking;
import com.navix.common.whatsapp.WhatsAppGateway;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.dto.MessagingDtos.ChannelPreview;
import com.navix.loan.dto.MessagingDtos.ChannelResult;
import com.navix.loan.dto.MessagingDtos.InputView;
import com.navix.loan.dto.MessagingDtos.LogPage;
import com.navix.loan.dto.MessagingDtos.LogRow;
import com.navix.loan.dto.MessagingDtos.PreviewRequest;
import com.navix.loan.dto.MessagingDtos.PreviewView;
import com.navix.loan.dto.MessagingDtos.SendRequest;
import com.navix.loan.dto.MessagingDtos.SendResult;
import com.navix.loan.dto.MessagingDtos.Target;
import com.navix.loan.dto.MessagingDtos.TemplateView;
import com.navix.loan.entity.BorrowerPreferences;
import com.navix.loan.entity.CustomerMessage;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.MessageTemplate;
import com.navix.loan.entity.RepaymentDiscountOffer;
import com.navix.loan.repository.BorrowerPreferencesRepository;
import com.navix.loan.repository.CustomerMessageRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.RepaymentDiscountOfferRepository;
import com.navix.loan.service.MessageCatalogue.Input;
import com.navix.loan.service.MessageCatalogue.Template;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

/**
 * ADMIN customer messaging (V95): sends approved templates by SMS and/or WhatsApp to one customer per
 * call and records every outcome in {@code customer_message}.
 *
 * <p><b>Deliberate exception to the event rule.</b> Everywhere else domain code publishes a Spring
 * event and the notification engine delivers asynchronously. Here the admin must see, per recipient and
 * per channel, whether the message was actually sent, so this service calls {@link WhatsAppGateway} and
 * {@link SmsGateway} directly and catches each channel's failure itself.
 *
 * <p>Intentionally <b>not</b> {@code @Transactional}: each ledger row (and the offer row) is saved by its
 * own repository call and so commits on its own, immediately after the provider call that produced it.
 * A later failure can therefore never roll back the record of a message that already left.
 */
@Service
@RequiredArgsConstructor
public class CustomerMessageService {

    private static final Logger log = LoggerFactory.getLogger(CustomerMessageService.class);

    public static final String FLAG = "admin-messaging";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime SEND_FROM = LocalTime.of(8, 0);
    private static final LocalTime SEND_UNTIL = LocalTime.of(19, 0);
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final Set<LoanStatus> LIVE_LOAN = EnumSet.of(
            LoanStatus.ACTIVE, LoanStatus.OVERDUE, LoanStatus.IN_COLLECTIONS);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MIN_PERCENT = 1;
    private static final int MAX_PERCENT = 7;
    private static final int MAX_REPAY_BY_DAYS = 30;
    private static final int MAX_LOG_PAGE = 100;

    private final FeatureFlagService featureFlags;
    private final WhatsAppGateway whatsApp;
    private final SmsGateway sms;
    private final LoanApplicationRepository applicationRepository;
    private final CustomerProfileRepository profileRepository;
    private final LoanRepository loanRepository;
    private final BorrowerPreferencesRepository preferencesRepository;
    private final CustomerMessageRepository messageRepository;
    private final RepaymentDiscountOfferRepository offerRepository;
    private final RepaymentService repaymentService;
    private final LoanMath loanMath;
    // Reads navix.sms.dlt-template-ids: an unmapped key silently falls back to the login OTP's DLT id.
    private final org.springframework.core.env.Environment environment;
    private final MessageTemplateService storedTemplates;
    private final MailPort mail;

    /** Overridable in tests so the 08:00 to 19:00 IST window can be exercised. */
    private Clock clock = Clock.system(IST);

    void setClock(Clock clock) {
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reads

    public List<TemplateView> templates() {
        requireAdmin();
        List<Template> all = new ArrayList<>(MessageCatalogue.ALL);
        if (featureFlags.isEnabled(FLAG, false)) {
            all.addAll(storedTemplates.sendable());
        }
        return all.stream()
                .map(t -> new TemplateView(t.key(), t.label(), t.category(), t.channels(),
                        t.inputs().stream().map(i -> new InputView(i.name(), i.label(), i.type())).toList(),
                        t.singleOnly(), t.confirmText()))
                .toList();
    }

    public LogPage log(int page, int size, Long customerId, String templateKey, String channel, String status,
                       LocalDate from, LocalDate to) {
        requireAdmin();
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), MAX_LOG_PAGE);
        Instant fromAt = from == null ? Instant.EPOCH : from.atStartOfDay(IST).toInstant();
        Instant toAt = to == null ? Instant.parse("3000-01-01T00:00:00Z")
                : to.plusDays(1).atStartOfDay(IST).toInstant();
        Page<CustomerMessage> result = messageRepository.search(customerId, blankToNull(templateKey),
                blankToNull(channel), blankToNull(status), fromAt, toAt, PageRequest.of(p - 1, s));
        Set<Long> appIds = result.getContent().stream().map(CustomerMessage::getApplicationId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = appIds.isEmpty() ? Map.of()
                : profileRepository.findByApplicationIdIn(appIds).stream()
                        .filter(pr -> pr.getFullName() != null)
                        .collect(Collectors.toMap(CustomerProfile::getApplicationId, CustomerProfile::getFullName,
                                (a, b) -> a));
        List<LogRow> rows = result.getContent().stream().map(m -> new LogRow(
                m.getId(), m.getCustomerId(), names.get(m.getApplicationId()), m.getApplicationId(),
                m.getLoanId(), m.getTemplateKey(), m.getProviderTemplate(), m.getChannel(),
                m.getMobile() != null ? Masking.maskPhone(m.getMobile())
                        : m.getEmail() != null ? Masking.maskEmail(m.getEmail()) : null, paramsOf(m.getParams()),
                m.getStatus(), m.getReason(),
                m.getDiscountPercent() == null ? null : m.getDiscountPercent().intValue(), m.getRepayBy(),
                m.getSentByName(), m.getCreatedAt())).toList();
        return new LogPage(rows, p, s, result.getTotalElements());
    }

    // ---------------------------------------------------------------- preview / send

    public PreviewView preview(PreviewRequest req) {
        Plan plan = plan(req.target(), req.templateKey(), req.channels(), req.inputs());
        return new PreviewView(plan.ctx.customerId, plan.ctx.profile == null ? null : plan.ctx.profile.getFullName(),
                plan.ctx.mobile() == null ? null : Masking.maskPhone(plan.ctx.mobile()),
                plan.channels.stream().map(c -> new ChannelPreview(c.channel, c.provider, c.params, c.skipReason,
                                c.subject == null ? c.smsText : c.subject + "\n\n" + c.smsText))
                        .toList());
    }

    public SendResult send(SendRequest req) {
        Plan plan = plan(req.target(), req.templateKey(), req.channels(), req.inputs());
        LocalTime now = LocalTime.now(clock);
        if (now.isBefore(SEND_FROM) || !now.isBefore(SEND_UNTIL)) {
            throw new BusinessException("OUTSIDE_SEND_HOURS",
                    "Messages can only be sent between 08:00 and 19:00 IST");
        }
        if (plan.template.confirmText() != null && !req.confirmed()) {
            throw new BusinessException("CONFIRMATION_REQUIRED", "Tick the confirmation to send this message");
        }
        List<ChannelResult> results = new ArrayList<>();
        Long firstSentMessageId = null;
        for (PlannedChannel c : plan.channels) {
            CustomerMessage row = baseRow(plan, c);
            if (c.skipReason != null) {
                row.setStatus(CustomerMessage.SKIPPED);
                row.setReason(c.skipReason);
            } else {
                try {
                    String ref = deliver(plan, c);
                    row.setStatus(CustomerMessage.SENT);
                    row.setProviderRef(truncate(ref, 200));
                } catch (RuntimeException e) {
                    log.warn("admin message failed customer={} template={} channel={}: {}",
                            plan.ctx.customerId, plan.template.key(), c.channel, e.getMessage());
                    row.setStatus(CustomerMessage.FAILED);
                    row.setReason(truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 1000));
                }
            }
            Long messageId = null;
            try {
                messageId = messageRepository.save(row).getId();
            } catch (RuntimeException e) {
                // The provider call has already happened: report it, never lose the other channels.
                log.error("admin message ledger write failed customer={} template={} channel={} status={}",
                        plan.ctx.customerId, plan.template.key(), c.channel, row.getStatus(), e);
            }
            if (messageId != null && firstSentMessageId == null && CustomerMessage.SENT.equals(row.getStatus())) {
                firstSentMessageId = messageId;
            }
            results.add(new ChannelResult(c.channel, row.getStatus(), row.getReason(), messageId));
        }
        boolean anySent = results.stream().anyMatch(r -> CustomerMessage.SENT.equals(r.status()));
        if (plan.template.discount() && anySent) {
            recordOffer(plan, firstSentMessageId);
        }
        return new SendResult(plan.ctx.customerId, results);
    }

    /** Returns the provider reference (email has none). A stored SMS always carries its own DLT id. */
    private String deliver(Plan plan, PlannedChannel c) {
        if (MessageTemplateService.EMAIL.equals(c.channel)) {
            if (!mail.send(plan.ctx.email(), c.subject, c.smsText)) {
                throw new IllegalStateException("Email was not accepted (email switched off or provider refused)");
            }
            return null;
        }
        String number = "91" + plan.ctx.mobile();
        if (!MessageCatalogue.SMS.equals(c.channel)) {
            return whatsApp.sendTemplate(number, c.provider, whatsAppParams(c.params));
        }
        return plan.stored != null
                ? sms.sendRegistered(number, c.smsText, c.provider)
                : sms.send(number, c.smsText, c.dltKey);
    }

    /** The offer exists only because a message went out; if it cannot be saved, say so loudly. */
    private void recordOffer(Plan plan, Long messageId) {
        try {
            RepaymentDiscountOffer offer = new RepaymentDiscountOffer();
            offer.setCustomerId(plan.ctx.customerId);
            offer.setLoanId(plan.ctx.loan.getId());
            offer.setPercent((short) plan.percent);
            offer.setRepayBy(plan.repayBy);
            offer.setMessageId(messageId);
            offer.setCreatedByStaffId(actorStaffId());
            offerRepository.save(offer);
        } catch (RuntimeException e) {
            log.error("discount offer not recorded customer={} loan={}", plan.ctx.customerId, plan.ctx.loan.getId(), e);
            throw new BusinessException("OFFER_NOT_RECORDED",
                    "The message was sent but the discount offer could not be saved. Check the log and "
                            + "tell the customer the offer is void, or ask engineering to record it.");
        }
    }

    // ---------------------------------------------------------------- planning

    private Plan plan(Target target, String templateKey, List<String> channelNames, Map<String, String> inputs) {
        requireAdmin();
        if (!featureFlags.isEnabled(FLAG, false)) {
            throw new BusinessException("FEATURE_DISABLED", "Customer messaging is switched off");
        }
        Template template = MessageCatalogue.find(templateKey);
        MessageTemplate stored = null;
        if (template == null && MessageTemplateService.isStoredKey(templateKey)) {
            stored = storedTemplates.requireSendable(templateKey);
            template = MessageTemplateService.adapt(stored);
        }
        if (template == null) {
            throw new BusinessException("INVALID_TEMPLATE", "Unknown message template");
        }
        if (channelNames == null || channelNames.isEmpty()) {
            throw new BusinessException("CHANNEL_REQUIRED", "Choose at least one channel");
        }
        Set<String> channels = new LinkedHashSet<>(channelNames);
        for (String c : channels) {
            if (!template.channels().contains(c)) {
                throw new BusinessException("INVALID_CHANNEL", "This template cannot be sent by " + c);
            }
        }
        Map<String, String> in = inputs == null ? Map.of() : inputs;
        int percent = 0;
        LocalDate repayBy = null;
        if (template.discount()) {
            percent = parsePercent(in.get("percent"));
            repayBy = parseRepayBy(in.get("repayBy"));
        }
        Ctx ctx = resolve(target);
        LocalDate today = LocalDate.now(clock);
        int daysOverdue = ctx.loan == null || ctx.loan.getDueDate() == null ? 0
                : loanMath.daysPastDue(ctx.loan.getDueDate(), today);
        BorrowerPreferences prefs = preferencesRepository.findByCustomerId(ctx.customerId).orElse(null);

        List<PlannedChannel> planned = new ArrayList<>();
        for (String channel : channels) {
            PlannedChannel pc = new PlannedChannel(channel, template.providerFor(daysOverdue));
            planned.add(pc);
            try {
                checkApplicable(template, ctx, daysOverdue);
                List<String> params = new ArrayList<>();
                for (String name : template.params()) {
                    params.add(value(name, template, ctx, channel, in, daysOverdue, percent, repayBy, today));
                }
                pc.params = params;
                if (stored != null) {
                    if (MessageCatalogue.SMS.equals(channel)) {
                        checkDltValues(params);
                    }
                    pc.smsText = MessageTemplateService.render(stored.getBody(), params);
                    pc.subject = MessageTemplateService.render(stored.getSubject(), params);
                } else if (MessageCatalogue.SMS.equals(channel) && template.discount()) {
                    boolean review = daysOverdue > ApplicationFlowService.LATE_REPAYMENT_TOLERANCE_DAYS;
                    Map<String, String> byName = MessageCatalogue.byName(template, params);
                    pc.dltKey = MessageCatalogue.discountSmsKey(percent, review);
                    pc.smsText = MessageCatalogue.discountSms(percent, review, byName.get("name"),
                            byName.get("loanId"), byName.get("repayBy"));
                    String id = environment.getProperty("navix.sms.dlt-template-ids." + pc.dltKey);
                    if (id == null || id.isBlank()) {
                        pc.skipReason = "SMS_NOT_REGISTERED";
                        continue;
                    }
                } else if (MessageCatalogue.SMS.equals(channel)) {
                    pc.dltKey = template.smsDltKey();
                    pc.smsText = MessageCatalogue.renderSms(template, MessageCatalogue.byName(template, params));
                }
            } catch (NotApplicable e) {
                pc.skipReason = "NOT_APPLICABLE: " + e.getMessage();
                pc.params = List.of();
                continue;
            }
            pc.skipReason = skipFor(template, ctx, prefs, channel);
        }
        return new Plan(template, ctx, planned, percent, repayBy, stored);
    }

    private String skipFor(Template template, Ctx ctx, BorrowerPreferences prefs, String channel) {
        boolean email = MessageTemplateService.EMAIL.equals(channel);
        String address = email ? ctx.email() : ctx.mobile();
        if (address == null || address.isBlank()) {
            return email ? "NO_EMAIL" : "NO_MOBILE";
        }
        // smsOptIn=false covers SMS and WhatsApp, matching BorrowerPreferenceAdapter.
        if (prefs != null && !(email ? prefs.isEmailOptIn() : prefs.isSmsOptIn())) {
            return "OPTED_OUT";
        }
        if (email && mail.isSuppressed(address)) {
            return "SUPPRESSED";
        }
        if (MessageCatalogue.MARKETING.equals(template.category()) && prefs != null && !prefs.isOffersOptIn()) {
            return "OFFERS_OPTED_OUT";
        }
        if (messageRepository.existsByCustomerIdAndTemplateKeyAndChannelAndStatusAndCreatedAtAfter(
                ctx.customerId, template.key(), channel, CustomerMessage.SENT,
                Instant.now(clock).minus(Duration.ofHours(24)))) {
            return "DUPLICATE_24H";
        }
        return null;
    }

    /** A registered {@code {#var#}} takes up to 30 characters, and anything non-ASCII breaks the DLT match. */
    private static void checkDltValues(List<String> values) {
        for (String v : values) {
            if (v.length() > 30) {
                throw new NotApplicable("a value is longer than the 30 characters DLT allows");
            }
            if (!StandardCharsets.US_ASCII.newEncoder().canEncode(v)) {
                throw new NotApplicable("a value has characters SMS cannot carry");
            }
        }
    }

    private static void checkApplicable(Template template, Ctx ctx, int daysOverdue) {
        if (template.rule() == MessageCatalogue.Rule.ANY) {
            return;
        }
        if (ctx.loan == null) {
            throw new NotApplicable("customer has no live loan");
        }
        switch (template.rule()) {
            case OVERDUE -> {
                if (daysOverdue < 1) {
                    throw new NotApplicable("loan is not overdue");
                }
            }
            case WINDOW_1_5 -> {
                if (daysOverdue < 1 || daysOverdue > ApplicationFlowService.LATE_REPAYMENT_TOLERANCE_DAYS) {
                    throw new NotApplicable("loan is not 1 to 5 days overdue");
                }
            }
            default -> { }
        }
    }

    private String value(String name, Template template, Ctx ctx, String channel, Map<String, String> in,
                         int daysOverdue, int percent, LocalDate repayBy, LocalDate today) {
        if (name.startsWith("in:")) {
            return inputValue(template, name.substring(3), in);
        }
        Loan loan = ctx.loan;
        return switch (name) {
            case "name" -> {
                String n = ctx.profile == null ? null : ctx.profile.getFullName();
                if (n == null || n.isBlank()) {
                    throw new NotApplicable("customer name is missing");
                }
                yield n.trim();
            }
            case "amount" -> money(repaymentService.outstandingAsOf(loan.getId(), today), channel);
            case "loanId" -> String.valueOf(loan.getId());
            case "daysOverdue" -> String.valueOf(daysOverdue);
            case "dailyCharge" -> money(loanMath.latePenaltyPaise(loan.getPrincipal(), 1), channel);
            case "dueDate" -> date(loan.getDueDate());
            case "dueDatePlus5" -> date(loan.getDueDate().plusDays(ApplicationFlowService.LATE_REPAYMENT_TOLERANCE_DAYS));
            case "salaryDate" -> {
                Integer day = salaryDay(ctx);
                if (day == null) {
                    throw new NotApplicable("no salary day on file");
                }
                yield date(nextSalaryDate(day, today));
            }
            case "repayBy" -> date(repayBy);
            case "percent" -> String.valueOf(percent);
            default -> throw new IllegalStateException("Unknown template parameter " + name);
        };
    }

    private static String inputValue(Template template, String name, Map<String, String> in) {
        Input spec = template.inputs().stream().filter(i -> i.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Template input missing: " + name));
        String raw = in.get(name) == null ? "" : in.get(name).strip();
        if (raw.isEmpty()) {
            throw new BusinessException("INPUT_REQUIRED", spec.label() + " is required");
        }
        if ("DATE".equals(spec.type())) {
            try {
                return date(LocalDate.parse(raw));
            } catch (DateTimeParseException e) {
                throw new BusinessException("INVALID_INPUT", spec.label() + " must be a valid date");
            }
        }
        if (raw.length() > 100 || raw.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException("INVALID_INPUT", spec.label() + " is not valid");
        }
        if ("amount".equals(name)) {
            String digits = raw.replace(",", "");
            if (!digits.matches("\\d{1,9}")) {
                throw new BusinessException("INVALID_INPUT", spec.label() + " must be a whole number of rupees");
            }
            return "Rs. " + String.format(Locale.ENGLISH, "%,d", Long.parseLong(digits));
        }
        return raw;
    }

    private static int parsePercent(String raw) {
        try {
            int p = Integer.parseInt(raw == null ? "" : raw.strip());
            if (p >= MIN_PERCENT && p <= MAX_PERCENT) {
                return p;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the error below
        }
        throw new BusinessException("INVALID_PERCENT", "Discount must be a whole number from 1 to 7");
    }

    private LocalDate parseRepayBy(String raw) {
        LocalDate today = LocalDate.now(clock);
        try {
            LocalDate d = LocalDate.parse(raw == null ? "" : raw.strip());
            if (!d.isBefore(today) && !d.isAfter(today.plusDays(MAX_REPAY_BY_DAYS))) {
                return d;
            }
        } catch (DateTimeParseException ignored) {
            // falls through to the error below
        }
        throw new BusinessException("INVALID_REPAY_BY", "Repay-by date must be from today to 30 days ahead");
    }

    // ---------------------------------------------------------------- target resolution

    private Ctx resolve(Target target) {
        if (target == null || (target.applicationId() == null) == (target.customerId() == null)) {
            throw new BusinessException("INVALID_TARGET", "Give either an applicationId or a customerId");
        }
        LoanApplication app;
        Long customerId;
        if (target.applicationId() != null) {
            app = applicationRepository.findById(target.applicationId()).orElseThrow(
                    () -> new ResourceNotFoundException("Application", String.valueOf(target.applicationId())));
            customerId = app.getCustomerId();
        } else {
            customerId = target.customerId();
            app = applicationRepository.findByCustomerId(customerId).stream()
                    .max(Comparator.comparing(LoanApplication::getId))
                    .orElseThrow(() -> new ResourceNotFoundException("Customer", String.valueOf(customerId)));
        }
        List<LoanApplication> apps = applicationRepository.findByCustomerId(customerId);
        CustomerProfile profile = profileRepository.findByApplicationId(app.getId()).orElse(null);
        if (profile == null || profile.getFullName() == null || profile.getFullName().isBlank()) {
            // The latest application can be a nameless draft; fall back to the newest saved profile.
            profile = apps.stream().sorted(Comparator.comparing(LoanApplication::getId).reversed())
                    .map(a -> profileRepository.findByApplicationId(a.getId()).orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .filter(p -> p.getFullName() != null && !p.getFullName().isBlank())
                    .findFirst().orElse(profile);
        }
        Loan loan = null;
        if (app.getLoanId() != null) {
            loan = loanRepository.findById(app.getLoanId()).filter(l -> LIVE_LOAN.contains(l.getStatus())).orElse(null);
        }
        if (loan == null) {
            loan = loanRepository.findByCustomerId(customerId).stream()
                    .filter(l -> LIVE_LOAN.contains(l.getStatus()))
                    .max(Comparator.comparing(Loan::getId)).orElse(null);
        }
        return new Ctx(customerId, app, apps, profile, loan);
    }

    private static Integer salaryDay(Ctx ctx) {
        if (ctx.app.getSalaryCreditDay() != null) {
            return ctx.app.getSalaryCreditDay();
        }
        return ctx.apps.stream().filter(a -> a.getSalaryCreditDay() != null)
                .max(Comparator.comparing(LoanApplication::getId)).map(LoanApplication::getSalaryCreditDay)
                .orElse(null);
    }

    /** The next date on or after {@code today} that falls on the salary day (clamped to the month length). */
    static LocalDate nextSalaryDate(int salaryDay, LocalDate today) {
        LocalDate thisMonth = today.withDayOfMonth(Math.min(Math.max(1, salaryDay), today.lengthOfMonth()));
        if (!thisMonth.isBefore(today)) {
            return thisMonth;
        }
        LocalDate next = today.withDayOfMonth(1).plusMonths(1);
        return next.withDayOfMonth(Math.min(Math.max(1, salaryDay), next.lengthOfMonth()));
    }

    // ---------------------------------------------------------------- helpers

    private CustomerMessage baseRow(Plan plan, PlannedChannel c) {
        CustomerMessage row = new CustomerMessage();
        row.setCustomerId(plan.ctx.customerId);
        row.setApplicationId(plan.ctx.app.getId());
        row.setLoanId(plan.ctx.loan == null ? null : plan.ctx.loan.getId());
        row.setTemplateKey(plan.template.key());
        row.setProviderTemplate(c.provider);
        row.setChannel(c.channel);
        if (MessageTemplateService.EMAIL.equals(c.channel)) {
            row.setEmail(plan.ctx.email());
        } else {
            row.setMobile(plan.ctx.mobile());
        }
        row.setParams(toJson(c.params));
        if (plan.template.discount()) {
            row.setDiscountPercent((short) plan.percent);
            row.setRepayBy(plan.repayBy);
        }
        row.setSentByStaffId(actorStaffId());
        row.setSentByName(ActorContext.get().name());
        return row;
    }

    private static List<String> whatsAppParams(List<String> params) {
        // Meta rejects an empty parameter; the notification engine's own blank is an em dash.
        return params.stream().map(p -> p == null || p.isBlank() ? "—" : p).toList();
    }

    private static String money(long paise, String channel) {
        String digits = String.format(Locale.ENGLISH, "%,d", paise / 100);
        // "Rs." on SMS: the rupee sign is outside GSM-7 and would force UCS-2 (same as TemplateRenderer).
        return (MessageCatalogue.SMS.equals(channel) ? "Rs. " : "₹") + digits;
    }

    private static String date(LocalDate d) {
        return DISPLAY_DATE.format(d);
    }

    private static String toJson(List<String> params) {
        try {
            return JSON.writeValueAsString(params == null ? List.of() : params);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private static List<String> paramsOf(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static void requireAdmin() {
        if (!"ADMIN".equals(ActorContext.get().role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "Only an admin can message customers");
        }
    }

    private static Long actorStaffId() {
        try {
            return Long.valueOf(ActorContext.get().id());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A template that does not fit this customer today; becomes a SKIPPED row, never an error. */
    private static final class NotApplicable extends RuntimeException {
        NotApplicable(String reason) {
            super(reason, null, false, false);
        }
    }

    private record Ctx(Long customerId, LoanApplication app, List<LoanApplication> apps, CustomerProfile profile,
                       Loan loan) {
        String mobile() {
            return profile == null ? null : profile.getMobile();
        }

        String email() {
            return profile == null ? null : profile.getEmail();
        }
    }

    private static final class PlannedChannel {
        final String channel;
        final String provider;
        List<String> params = List.of();
        String smsText;
        String dltKey;
        String subject;
        String skipReason;

        PlannedChannel(String channel, String provider) {
            this.channel = channel;
            this.provider = provider;
        }
    }

    private record Plan(Template template, Ctx ctx, List<PlannedChannel> channels, int percent, LocalDate repayBy,
                        MessageTemplate stored) {
    }
}
