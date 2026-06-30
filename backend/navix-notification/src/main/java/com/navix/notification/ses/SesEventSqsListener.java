package com.navix.notification.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.notification.NotificationChannel;
import com.navix.notification.channel.DeliveryStatus;
import com.navix.notification.entity.NotificationDelivery;
import com.navix.notification.repository.NotificationDeliveryRepository;
import com.navix.notification.suppression.EmailSuppressionService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes SES bounce/complaint events from the SQS queue fed by the SES configuration set
 * ({@code navix-notifications}) → SNS topic → SQS. Off unless {@code navix.ses.events.enabled=true}
 * so the app boots locally without an SQS queue.
 *
 * <p>A hard (Permanent) bounce or any complaint adds every affected recipient to the
 * {@link EmailSuppressionService} list and flips the originating {@link NotificationDelivery} row
 * (matched by the SES messageId stored in {@code provider_ref}) to {@code BOUNCED}/{@code COMPLAINED}.
 * Transient bounces are logged but not suppressed. Malformed/unknown payloads are logged and
 * swallowed so they don't redeliver forever; genuine DB failures propagate so SQS retries → DLQ.
 */
@Component
@ConditionalOnProperty(name = "navix.ses.events.enabled", havingValue = "true")
public class SesEventSqsListener {

    private static final Logger log = LoggerFactory.getLogger(SesEventSqsListener.class);

    private final ObjectMapper mapper;
    private final EmailSuppressionService suppressionService;
    private final NotificationDeliveryRepository deliveryRepo;

    public SesEventSqsListener(ObjectMapper mapper,
                               EmailSuppressionService suppressionService,
                               NotificationDeliveryRepository deliveryRepo) {
        this.mapper = mapper;
        this.suppressionService = suppressionService;
        this.deliveryRepo = deliveryRepo;
    }

    @SqsListener("${navix.ses.events.queue}")
    public void onSesEvent(String body) {
        JsonNode event;
        try {
            event = unwrap(mapper.readTree(body));
        } catch (Exception e) {
            log.warn("SES event: unparseable payload, dropping. err={}", e.getMessage());
            return;
        }

        String kind = text(event, "eventType");
        if (kind == null) {
            kind = text(event, "notificationType"); // legacy SNS notification shape
        }
        if (kind == null) {
            log.debug("SES event: no eventType/notificationType, ignoring");
            return;
        }

        String messageId = event.path("mail").path("messageId").asText(null);

        switch (kind) {
            case "Bounce" -> handleBounce(event.path("bounce"), messageId);
            case "Complaint" -> handleComplaint(event.path("complaint"), messageId);
            default -> log.debug("SES event {} ignored (messageId={})", kind, messageId);
        }
    }

    private void handleBounce(JsonNode bounce, String messageId) {
        String bounceType = bounce.path("bounceType").asText("");
        String subType = bounce.path("bounceSubType").asText(null);
        if (!"Permanent".equalsIgnoreCase(bounceType)) {
            log.info("SES transient bounce ({}), not suppressing. messageId={}", bounceType, messageId);
            return;
        }
        for (JsonNode r : bounce.path("bouncedRecipients")) {
            String email = r.path("emailAddress").asText(null);
            String detail = r.path("diagnosticCode").asText(null);
            suppressAndMark(email, "BOUNCE", subType, messageId, detail, DeliveryStatus.BOUNCED);
        }
    }

    private void handleComplaint(JsonNode complaint, String messageId) {
        String subType = complaint.path("complaintFeedbackType").asText(null);
        for (JsonNode r : complaint.path("complainedRecipients")) {
            String email = r.path("emailAddress").asText(null);
            suppressAndMark(email, "COMPLAINT", subType, messageId, null, DeliveryStatus.COMPLAINED);
        }
    }

    private void suppressAndMark(String email, String reason, String subType, String messageId,
                                 String detail, DeliveryStatus newStatus) {
        if (email == null || email.isBlank()) {
            return;
        }
        suppressionService.suppress(email, reason, subType, messageId, detail);
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        List<NotificationDelivery> rows =
                deliveryRepo.findByProviderRefAndChannel(messageId, NotificationChannel.EMAIL);
        for (NotificationDelivery row : rows) {
            row.setStatus(newStatus);
            row.setError(reason + (subType == null ? "" : ":" + subType));
        }
        if (!rows.isEmpty()) {
            deliveryRepo.saveAll(rows);
        }
    }

    /** With raw SNS delivery the body is the SES event itself; unwrap if an SNS envelope sneaks in. */
    private JsonNode unwrap(JsonNode node) throws Exception {
        if (node.has("Type") && "Notification".equals(node.path("Type").asText())
                && node.has("Message")) {
            return mapper.readTree(node.path("Message").asText());
        }
        return node;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
