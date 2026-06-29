package com.navix.iam.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.iam.entity.CompanyExpense;
import com.navix.iam.repository.CompanyExpenseRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** Every expense, most recent first. */
    @Transactional(readOnly = true)
    public List<CompanyExpense> list() {
        requireAdmin();
        return expenseRepository.findAllByOrderByExpenseDateDescIdDesc();
    }

    /** Record a new expense. {@code expenseDate} defaults to today when not supplied. */
    @Transactional
    public CompanyExpense add(String description, long amountPaise, String paidTo, String notes,
                             LocalDate expenseDate) {
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
        return expenseRepository.save(expense);
    }

    /** Delete an expense by id. */
    @Transactional
    public void remove(Long id) {
        requireAdmin();
        CompanyExpense expense = expenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CompanyExpense", String.valueOf(id)));
        expenseRepository.delete(expense);
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
