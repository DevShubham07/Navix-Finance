package com.navix.iam.repository;

import com.navix.iam.entity.CompanyExpense;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@link CompanyExpense} (the company expense ledger). */
public interface CompanyExpenseRepository extends JpaRepository<CompanyExpense, Long> {

    /** All expenses, most recent expense date first (ties broken by newest id). */
    List<CompanyExpense> findAllByOrderByExpenseDateDescIdDesc();
}
