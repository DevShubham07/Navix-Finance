package com.navix.iam.dto;

import com.navix.iam.entity.CompanyExpense;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Request/response payloads for the ADMIN company-expense ledger. Views are mapped from the entity
 * via the static {@code of(...)} factory (mirrors the IAM module convention). Money is integer paise.
 */
public final class ExpenseDtos {

    private ExpenseDtos() {
    }

    /** Admin records a company expense. {@code expenseDate} is optional (defaults to today). */
    public record AddExpenseRequest(
            @NotBlank String description,
            @Positive long amountPaise,
            @NotBlank String paidTo,
            String notes,
            LocalDate expenseDate
    ) {
    }

    /** Standard expense representation returned to clients ({@code addedBy} = the recording admin). */
    public record ExpenseResponse(
            Long id,
            String description,
            long amountPaise,
            String paidTo,
            String notes,
            LocalDate expenseDate,
            Instant createdAt,
            String addedBy
    ) {

        public static ExpenseResponse of(CompanyExpense e) {
            return new ExpenseResponse(e.getId(), e.getDescription(), e.getAmountPaise(),
                    e.getPaidTo(), e.getNotes(), e.getExpenseDate(), e.getCreatedAt(), e.getCreatedBy());
        }
    }
}
