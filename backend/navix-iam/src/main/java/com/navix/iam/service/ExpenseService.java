package com.navix.iam.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.iam.dto.ExpenseDtos.ExpenseResponse;
import com.navix.iam.entity.CompanyExpense;
import com.navix.iam.repository.CompanyExpenseRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Company expense ledger administration, backed by {@link CompanyExpenseRepository}.
 *
 * <p>ADMIN-only on every operation (read + write): the sheet is for the administrator alone, so a
 * non-admin actor is rejected with {@code FORBIDDEN_ROLE} (HTTP 422). Money is integer paise.
 */
@Service
@RequiredArgsConstructor
public class ExpenseService {

    private final CompanyExpenseRepository expenseRepository;
    private final DocumentStoragePort storage;

    /** Every expense, most recent first, with attachment keys turned into presigned URLs. */
    @Transactional(readOnly = true)
    public List<ExpenseResponse> list() {
        requireAdmin();
        return expenseRepository.findAllByOrderByExpenseDateDescIdDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Record a new expense. {@code expenseDate} defaults to today when not supplied;
     * {@code receiptObjectKey} is an optional already-uploaded attachment's S3 key.
     */
    @Transactional
    public ExpenseResponse add(String description, long amountPaise, String paidTo, String notes,
                             LocalDate expenseDate, String receiptObjectKey) {
        requireAdmin();
        if (amountPaise <= 0) {
            throw new BusinessException("INVALID_AMOUNT", "Expense amount must be positive");
        }
        CompanyExpense expense = new CompanyExpense();
        expense.setDescription(description.trim());
        expense.setAmountPaise(amountPaise);
        expense.setPaidTo(paidTo.trim());
        expense.setNotes(trimToNull(notes));
        expense.setExpenseDate(expenseDate != null ? expenseDate : LocalDate.now());
        expense.setReceiptObjectKey(trimToNull(receiptObjectKey));
        return toResponse(expenseRepository.save(expense));
    }

    /** Delete an expense by id. */
    @Transactional
    public void remove(Long id) {
        requireAdmin();
        CompanyExpense expense = expenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CompanyExpense", String.valueOf(id)));
        expenseRepository.delete(expense);
    }

    /** Map an entity to a DTO, presigning the attachment key into a short-lived URL (or null). */
    private ExpenseResponse toResponse(CompanyExpense e) {
        String url = StringUtils.hasText(e.getReceiptObjectKey())
                ? storage.presignDownload(e.getReceiptObjectKey())
                : null;
        return ExpenseResponse.of(e, url);
    }

    private static void requireAdmin() {
        CurrentActor actor = ActorContext.get();
        if (actor == null || !"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ADMIN");
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
