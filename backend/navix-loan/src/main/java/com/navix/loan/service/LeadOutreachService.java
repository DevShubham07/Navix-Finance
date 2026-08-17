package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.notification.MailPort;
import com.navix.common.sms.SmsGateway;
import com.navix.loan.dto.DsaDtos.OutreachRequest;
import com.navix.loan.dto.DsaDtos.OutreachResultView;
import com.navix.loan.entity.Lead;
import com.navix.loan.entity.LeadOutreach;
import com.navix.loan.repository.LeadOutreachRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends outreach from a DSA to their own lead — free-text email, or a fixed-template SMS (V55).
 * A lead is not a customer/staff recipient, so the notification catalog/dispatcher is not a fit;
 * this follows the same direct-client precedent as {@code ContactService}/{@code EmailOtpService}
 * (navix-app), reaching {@link SmsGateway} directly (already visible from navix-loan) and email via
 * the {@link MailPort} seam (navix-loan does not depend on navix-notification).
 *
 * <p>Abuse controls: free-text email out of the platform's SES domain is a reputation risk, so
 * every send — success or failure — is persisted with its full body (visible to ADMIN), and both a
 * per-lead (3/day) and a per-DSA (50/day) rate limit apply.
 */
@Service
@RequiredArgsConstructor
public class LeadOutreachService {

    private static final Logger log = LoggerFactory.getLogger(LeadOutreachService.class);

    private static final int MAX_PER_LEAD_PER_DAY = 3;
    private static final int MAX_PER_DSA_PER_DAY = 50;

    /** The DSA cannot supply SMS text — a fixed, DLT-registered template only. */
    private static final String SMS_TEMPLATE =
            "DhanBoost: You were referred for a salary-linked advance. Apply at dhanboost.com to check your offer.";

    private final SmsGateway smsGateway;
    private final MailPort mailPort;
    private final LeadOutreachRepository outreachRepository;

    @Transactional
    public OutreachResultView send(Lead lead, Long dsaStaffId, OutreachRequest req) {
        String channel = req.channel() == null ? null : req.channel().trim().toUpperCase();
        if (!"SMS".equals(channel) && !"EMAIL".equals(channel)) {
            throw new BusinessException("INVALID_CHANNEL", "channel must be SMS or EMAIL");
        }
        Instant since = Instant.now().minus(1, ChronoUnit.DAYS);
        if (outreachRepository.countByLeadIdAndCreatedAtAfter(lead.getId(), since) >= MAX_PER_LEAD_PER_DAY) {
            throw new BusinessException("OUTREACH_RATE_LIMITED", "You've reached today's outreach limit for this lead");
        }
        if (outreachRepository.countByDsaStaffIdAndCreatedAtAfter(dsaStaffId, since) >= MAX_PER_DSA_PER_DAY) {
            throw new BusinessException("OUTREACH_RATE_LIMITED", "You've reached today's outreach limit");
        }
        return "SMS".equals(channel) ? sendSms(lead, dsaStaffId) : sendEmail(lead, dsaStaffId, req);
    }

    private OutreachResultView sendSms(Lead lead, Long dsaStaffId) {
        LeadOutreach row = newRow(lead, dsaStaffId, "SMS", lead.getMobile(), null, SMS_TEMPLATE);
        try {
            String ref = smsGateway.send("91" + lead.getMobile(), SMS_TEMPLATE, "DSA_LEAD_INVITE");
            row.setStatus("SENT");
            row.setProviderRef(ref);
        } catch (Exception e) {
            // Never let a transport failure propagate — record it, don't throw.
            log.warn("DSA lead SMS send failed for lead {}: {}", lead.getId(), e.getMessage());
            row.setStatus("FAILED");
            row.setError(truncate(e.getMessage()));
        }
        outreachRepository.save(row);
        return new OutreachResultView("SMS", row.getStatus(), row.getError());
    }

    private OutreachResultView sendEmail(Lead lead, Long dsaStaffId, OutreachRequest req) {
        String subject = req.subject() == null || req.subject().isBlank()
                ? "A message from your DhanBoost DSA" : req.subject().trim();
        String body = req.body() == null ? "" : req.body().trim();
        LeadOutreach row = newRow(lead, dsaStaffId, "EMAIL", lead.getEmail(), subject, body);
        if (lead.getEmail() == null || lead.getEmail().isBlank()) {
            row.setStatus("SKIPPED");
            row.setError("NO_EMAIL");
            outreachRepository.save(row);
            return new OutreachResultView("EMAIL", "SKIPPED", "NO_EMAIL");
        }
        boolean ok = mailPort.send(lead.getEmail(), subject, body);
        row.setStatus(ok ? "SENT" : "FAILED");
        if (!ok) {
            row.setError("delivery failed");
        }
        outreachRepository.save(row);
        return new OutreachResultView("EMAIL", row.getStatus(), row.getError());
    }

    private static LeadOutreach newRow(Lead lead, Long dsaStaffId, String channel, String address,
                                       String subject, String body) {
        LeadOutreach row = new LeadOutreach();
        row.setLeadId(lead.getId());
        row.setDsaStaffId(dsaStaffId);
        row.setChannel(channel);
        row.setAddress(address == null ? "" : address);
        row.setSubject(subject);
        row.setBody(body);
        row.setCreatedAt(Instant.now());
        return row;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 240 ? s.substring(0, 240) : s;
    }
}
