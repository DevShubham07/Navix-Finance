package com.navix.notification.dispatch;

import com.navix.common.loan.BorrowerPreferenceDirectory;
import com.navix.common.loan.LoanDirectory;
import com.navix.common.notification.ContactInfo;
import com.navix.common.notification.NotificationChannel;
import com.navix.common.notification.RecipientType;
import com.navix.notification.audience.AudienceResolver;
import com.navix.notification.catalog.NotificationType;
import com.navix.notification.channel.ChannelSender;
import com.navix.notification.channel.DeliveryOutcome;
import com.navix.notification.channel.DeliveryStatus;
import com.navix.notification.entity.Notification;
import com.navix.notification.entity.NotificationDelivery;
import com.navix.notification.repository.NotificationDeliveryRepository;
import com.navix.notification.repository.NotificationRepository;
import com.navix.notification.template.NotificationFormat;
import com.navix.notification.template.RenderedMessage;
import com.navix.notification.template.TemplateRenderer;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The heart of the engine: resolves a {@link NotificationType} + {@link NotificationContext} into
 * concrete recipients, renders per-channel messages, persists one {@link Notification} per recipient,
 * and records a {@link NotificationDelivery} per channel. Runs in its own transaction (the async
 * listener has none). <b>Error isolation:</b> one recipient/channel failure never aborts the rest.
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final int ERROR_MAX = 990;

    private final TemplateRenderer renderer;
    private final AudienceResolver audienceResolver;
    private final NotificationRepository notificationRepo;
    private final NotificationDeliveryRepository deliveryRepo;
    private final LoanDirectory loanDirectory;
    private final BorrowerPreferenceDirectory borrowerPreferences;
    private final Map<NotificationChannel, ChannelSender> senders = new EnumMap<>(NotificationChannel.class);

    public NotificationDispatcher(TemplateRenderer renderer, AudienceResolver audienceResolver,
                                  NotificationRepository notificationRepo,
                                  NotificationDeliveryRepository deliveryRepo,
                                  LoanDirectory loanDirectory, BorrowerPreferenceDirectory borrowerPreferences,
                                  List<ChannelSender> channelSenders) {
        this.renderer = renderer;
        this.audienceResolver = audienceResolver;
        this.notificationRepo = notificationRepo;
        this.deliveryRepo = deliveryRepo;
        this.loanDirectory = loanDirectory;
        this.borrowerPreferences = borrowerPreferences;
        for (ChannelSender sender : channelSenders) {
            senders.put(sender.channel(), sender);
        }
    }

    @Transactional
    public void dispatch(NotificationType type, NotificationContext ctx) {
        List<ContactInfo> recipients = audienceResolver.resolve(type.audience(), ctx);
        if (recipients.isEmpty()) {
            log.debug("No recipients resolved for {} (app={}, loan={})", type, ctx.applicationId(), ctx.loanId());
            return;
        }
        Map<String, Object> baseModel = baseModel(ctx);

        for (ContactInfo recipient : recipients) {
            Map<String, Object> model = new HashMap<>(baseModel);
            model.put("name", recipient.name() == null ? "there" : recipient.name());
            model.put("role", recipient.role());

            Notification saved = persistNotification(type, ctx, recipient, model);
            // Borrowers may opt out of SMS / EMAIL (server-persisted prefs); IN_APP (the inbox row
            // above) is never suppressed. Staff recipients are unaffected.
            java.util.Set<NotificationChannel> optedOut = recipient.type() == RecipientType.BORROWER
                    ? borrowerPreferences.optedOutChannels(recipient.id())
                    : java.util.Set.of();
            for (NotificationChannel channel : type.channels()) {
                if (channel != NotificationChannel.IN_APP && optedOut.contains(channel)) {
                    recordSkippedOptOut(saved, channel, recipient);
                    continue;
                }
                deliver(saved, channel, type, recipient, model);
            }
        }
    }

    /** Persist a {@code SKIPPED} delivery for an opted-out channel (audit trail, mirrors NO_MOBILE). */
    private void recordSkippedOptOut(Notification saved, NotificationChannel channel, ContactInfo recipient) {
        try {
            NotificationDelivery d = new NotificationDelivery();
            d.setNotificationId(saved.getId());
            d.setChannel(channel);
            d.setStatus(DeliveryStatus.SKIPPED);
            d.setError("OPTED_OUT");
            deliveryRepo.save(d);
        } catch (Exception e) {
            log.warn("Could not record opted-out delivery for notification {} channel {}: {}",
                    saved.getId(), channel, e.toString());
        }
    }

    private Notification persistNotification(NotificationType type, NotificationContext ctx,
                                             ContactInfo recipient, Map<String, Object> model) {
        RenderedMessage display = displayMessage(type, model);
        Notification n = new Notification();
        n.setRecipientType(recipient.type());
        n.setRecipientId(recipient.id());
        n.setType(type);
        n.setCategory(type.category());
        n.setTitle(display.subject() != null ? display.subject() : type.name());
        n.setBody(display.body() != null ? display.body() : "");
        n.setInApp(type.channels().contains(NotificationChannel.IN_APP));
        n.setApplicationId(ctx.applicationId());
        n.setLoanId(ctx.loanId());
        n.setCaseId(ctx.caseId());
        n.setActorId(ctx.actorId());
        n.setActorRole(ctx.actorRole());
        return notificationRepo.save(n);
    }

    private void deliver(Notification notification, NotificationChannel channel, NotificationType type,
                         ContactInfo recipient, Map<String, Object> model) {
        NotificationDelivery d = new NotificationDelivery();
        d.setNotificationId(notification.getId());
        d.setChannel(channel);
        d.setAddress(addressFor(channel, recipient));
        d.setAttempts(0);
        try {
            RenderedMessage message = renderer.render(type, channel, model);
            ChannelSender sender = senders.get(channel);
            if (message == null) {
                d.setStatus(DeliveryStatus.SKIPPED);
                d.setError("NO_TEMPLATE");
            } else if (sender == null) {
                d.setStatus(DeliveryStatus.SKIPPED);
                d.setError("NO_SENDER");
            } else {
                d.setAttempts(1);
                DeliveryOutcome outcome = sender.send(message, recipient);
                d.setStatus(outcome.status());
                d.setProviderRef(outcome.providerRef());
                d.setError(truncate(outcome.error()));
            }
        } catch (RuntimeException e) {
            // Senders never throw, but isolate defensively so one channel can't sink the rest.
            d.setStatus(DeliveryStatus.FAILED);
            d.setError(truncate(e.getMessage()));
            log.warn("Delivery {} via {} threw: {}", type, channel, e.getMessage());
        }
        deliveryRepo.save(d);
    }

    /** The template used for the persisted in-app row's title/body: IN_APP, else EMAIL, else SMS. */
    private RenderedMessage displayMessage(NotificationType type, Map<String, Object> model) {
        NotificationChannel preferred = type.channels().contains(NotificationChannel.IN_APP)
                ? NotificationChannel.IN_APP
                : type.channels().contains(NotificationChannel.EMAIL)
                        ? NotificationChannel.EMAIL
                        : NotificationChannel.SMS;
        RenderedMessage message = renderer.render(type, preferred, model);
        if (message == null) {
            for (NotificationChannel channel : type.channels()) {
                message = renderer.render(type, channel, model);
                if (message != null) {
                    break;
                }
            }
        }
        return message != null ? message : new RenderedMessage(preferred, type.name(), "", null);
    }

    /** Base model: the event-supplied values + (when a loan is in scope) its money/date fields. */
    private Map<String, Object> baseModel(NotificationContext ctx) {
        Map<String, Object> model = new HashMap<>(ctx.model());
        if (ctx.applicationId() != null) {
            model.putIfAbsent("applicationId", ctx.applicationId());
        }
        if (ctx.loanId() != null) {
            model.putIfAbsent("loanId", ctx.loanId());
            loanDirectory.findLoan(ctx.loanId()).ifPresent(loan -> {
                model.put("netDisbursed", NotificationFormat.inr(loan.netDisbursedPaise()));
                model.put("totalRepayable", NotificationFormat.inr(loan.totalRepayablePaise()));
                model.put("outstanding", NotificationFormat.inr(loan.outstandingPaise()));
                model.put("dueDate", NotificationFormat.date(loan.dueDate()));
            });
        }
        return model;
    }

    private static String addressFor(NotificationChannel channel, ContactInfo recipient) {
        return switch (channel) {
            case SMS -> recipient.mobile();
            case EMAIL -> recipient.email();
            case IN_APP -> null;
        };
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= ERROR_MAX ? s : s.substring(0, ERROR_MAX);
    }
}
