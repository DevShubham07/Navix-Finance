package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.security.ActorContext;
import com.navix.loan.dto.MessagingDtos.AllowanceView;
import com.navix.loan.dto.MessagingDtos.ReapplyAllowanceStatus;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.ProfileChangeLog;
import com.navix.loan.entity.ReapplyAllowance;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.ProfileChangeLogRepository;
import com.navix.loan.repository.ReapplyAllowanceRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN "Allow re-application" (V95): a one-use, documented exception for a customer the
 * repayment-history rule turns away. It does not touch payment data or {@code paidBeyondTolerance};
 * {@code ApplicationFlowService.reborrow} consumes it and sends the new application to credit review.
 */
@Service
@RequiredArgsConstructor
public class ReapplyAllowanceService {

    private final ReapplyAllowanceRepository allowanceRepository;
    private final LoanApplicationRepository applicationRepository;
    private final ApplicationDocumentRepository documentRepository;
    private final ProfileChangeLogRepository changeLogRepository;
    private final ApplicationFlowService flowService;

    @Transactional(readOnly = true)
    public ReapplyAllowanceStatus status(Long customerId) {
        requireAdmin();
        List<LoanApplication> apps = requireCustomer(customerId);
        return new ReapplyAllowanceStatus(flowService.isDisqualified(customerId),
                unused(customerId).map(ReapplyAllowanceService::view).orElse(null));
    }

    @Transactional
    public AllowanceView grant(Long customerId, String reason, Long documentId) {
        requireAdmin();
        List<LoanApplication> apps = requireCustomer(customerId);
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("REASON_REQUIRED", "A reason is required");
        }
        if (documentId == null) {
            throw new BusinessException("DOCUMENT_REQUIRED", "A supporting document is required");
        }
        ApplicationDocument doc = documentRepository.findById(documentId)
                .filter(d -> apps.stream().anyMatch(a -> a.getId().equals(d.getApplicationId())))
                .orElseThrow(() -> new BusinessException("INVALID_DOCUMENT",
                        "The document does not exist for this customer"));
        if (!flowService.isDisqualified(customerId)) {
            throw new BusinessException("NOT_DISQUALIFIED", "This customer is not currently disqualified");
        }
        if (unused(customerId).isPresent()) {
            throw new BusinessException("ALLOWANCE_EXISTS", "An unused re-application allowance already exists");
        }
        ReapplyAllowance row = new ReapplyAllowance();
        row.setCustomerId(customerId);
        row.setReason(reason.strip());
        row.setDocumentId(doc.getId());
        row.setGrantedByStaffId(actorStaffId());
        row.setGrantedByName(ActorContext.get().name());
        ReapplyAllowance saved = allowanceRepository.save(row);

        // Audit the way the other ADMIN corrections do: a previous-to-new row on the customer's change log.
        ProfileChangeLog entry = new ProfileChangeLog();
        entry.setCustomerId(customerId);
        entry.setApplicationId(apps.stream().map(LoanApplication::getId).max(Long::compare).orElse(null));
        entry.setField("reapplyAllowance");
        entry.setOldValue(null);
        entry.setNewValue("Allowed re-application: " + row.getReason() + " (document #" + doc.getId() + ")");
        changeLogRepository.save(entry);
        return view(saved);
    }

    private java.util.Optional<ReapplyAllowance> unused(Long customerId) {
        return allowanceRepository.findFirstByCustomerIdAndUsedApplicationIdIsNullOrderByIdAsc(customerId);
    }

    private List<LoanApplication> requireCustomer(Long customerId) {
        List<LoanApplication> apps = applicationRepository.findByCustomerId(customerId);
        if (apps.isEmpty()) {
            throw new ResourceNotFoundException("Customer", String.valueOf(customerId));
        }
        return apps;
    }

    private static AllowanceView view(ReapplyAllowance a) {
        return new AllowanceView(a.getId(), a.getCustomerId(), a.getReason(), a.getDocumentId(), a.getGrantedByName(), a.getGrantedAt());
    }

    private static void requireAdmin() {
        if (!"ADMIN".equals(ActorContext.get().role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "Only an admin can allow re-application");
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
