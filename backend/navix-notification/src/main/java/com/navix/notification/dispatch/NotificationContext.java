package com.navix.notification.dispatch;

import com.navix.common.notification.ContactInfo;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Everything the dispatcher needs to deliver one notification, carried from the listener (which has
 * the event data but no transaction/ActorContext). Identifiers drive audience resolution + the saved
 * row's foreign keys; {@code model} carries the display-ready template values. {@code explicitStaffSubject}
 * lets staff/IAM events supply the recipient directly (an invited subject has no staff row yet).
 */
public record NotificationContext(
        Long customerId,
        Long applicationId,
        Long loanId,
        UUID caseId,
        Long assignedExecutiveId,
        Long staffSubjectId,
        ContactInfo explicitStaffSubject,
        String actorId,
        String actorRole,
        Map<String, Object> model) {

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder — the listeners assemble a context field-by-field. */
    public static final class Builder {
        private Long customerId;
        private Long applicationId;
        private Long loanId;
        private UUID caseId;
        private Long assignedExecutiveId;
        private Long staffSubjectId;
        private ContactInfo explicitStaffSubject;
        private String actorId;
        private String actorRole;
        private final Map<String, Object> model = new HashMap<>();

        public Builder customerId(Long v) { this.customerId = v; return this; }
        public Builder applicationId(Long v) { this.applicationId = v; return this; }
        public Builder loanId(Long v) { this.loanId = v; return this; }
        public Builder caseId(UUID v) { this.caseId = v; return this; }
        public Builder assignedExecutiveId(Long v) { this.assignedExecutiveId = v; return this; }
        public Builder staffSubjectId(Long v) { this.staffSubjectId = v; return this; }
        public Builder explicitStaffSubject(ContactInfo v) { this.explicitStaffSubject = v; return this; }
        public Builder actorId(String v) { this.actorId = v; return this; }
        public Builder actorRole(String v) { this.actorRole = v; return this; }

        public Builder put(String key, Object value) {
            this.model.put(key, value);
            return this;
        }

        public NotificationContext build() {
            return new NotificationContext(customerId, applicationId, loanId, caseId, assignedExecutiveId,
                    staffSubjectId, explicitStaffSubject, actorId, actorRole, model);
        }
    }
}
