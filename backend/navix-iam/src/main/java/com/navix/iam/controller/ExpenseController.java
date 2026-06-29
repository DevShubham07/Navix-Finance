package com.navix.iam.controller;

import com.navix.common.web.ApiResponse;
import com.navix.iam.dto.ExpenseDtos.AddExpenseRequest;
import com.navix.iam.dto.ExpenseDtos.ExpenseResponse;
import com.navix.iam.service.ExpenseService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only company expense ledger (description, amount, payee, notes). Every operation is
 * ADMIN-gated in {@link ExpenseService} (non-admin → {@code FORBIDDEN_ROLE}).
 */
@RestController
@RequestMapping("/api/admin/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenseService;

    /** List all expenses, most recent first. */
    @GetMapping
    public ApiResponse<List<ExpenseResponse>> list() {
        return ApiResponse.ok(expenseService.list().stream()
                .map(ExpenseResponse::of)
                .toList());
    }

    /** Record a new expense. */
    @PostMapping
    public ApiResponse<ExpenseResponse> add(@Valid @RequestBody AddExpenseRequest request) {
        return ApiResponse.ok(ExpenseResponse.of(expenseService.add(
                request.description(), request.amountPaise(), request.paidTo(),
                request.notes(), request.expenseDate())));
    }

    /** Delete an expense. */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        expenseService.remove(id);
        return ApiResponse.ok(null);
    }
}
