package com.navix.loan.repository;

import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.entity.LoanApplication;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence for {@link LoanApplication} — the application aggregate. */
@Repository
public interface LoanApplicationRepository
        extends JpaRepository<LoanApplication, Long>, JpaSpecificationExecutor<LoanApplication> {

    List<LoanApplication> findByCustomerId(Long customerId);

    /** The application that minted a given loan (set at activation); for loan→borrower resolution. */
    Optional<LoanApplication> findByLoanId(Long loanId);

    /**
     * The applications that minted a batch of loans, in one query — backs {@code
     * LoanRegisterService}'s bulk "loan → application" resolution so the staff loan register costs a
     * fixed number of queries instead of loading every application in the database. Callers MUST
     * short-circuit on an empty collection; {@code in ()} is not valid SQL.
     */
    List<LoanApplication> findByLoanIdIn(Collection<Long> loanIds);

    /** Drives the Credit Head queue (no date filter there) — newest first. */
    List<LoanApplication> findByStatusOrderByCreatedAtDescIdDesc(ApplicationStatus status);

    List<LoanApplication> findByAssignedExecutiveIdAndStatusOrderByCreatedAtDescIdDesc(
            Long assignedExecutiveId, ApplicationStatus status);

    /**
     * Customer ids of every application currently assigned to one executive. Backs the scoped
     * Customers list — an executive sees only their own book, and this resolves half of it in a
     * single query rather than pulling applications into memory.
     */
    @Query("select distinct a.customerId from LoanApplication a where a.assignedExecutiveId = :executiveId")
    Set<Long> findCustomerIdsByAssignedExecutiveId(@Param("executiveId") Long executiveId);

    /**
     * Customer ids behind a set of application ids — the other half of the scoped Customers list
     * (applications the caller has recorded a decision on). Callers MUST short-circuit on an empty
     * collection; {@code in ()} is not valid SQL.
     */
    @Query("select distinct a.customerId from LoanApplication a where a.id in :applicationIds")
    Set<Long> findCustomerIdsByIdIn(@Param("applicationIds") Collection<Long> applicationIds);

    /** Application count per status, for the staff dashboard pipeline (statuses with no rows are absent). */
    @Query("select a.status as status, count(a) as count from LoanApplication a group by a.status")
    List<StatusCount> countGroupByStatus();

    /** Projection for {@link #countGroupByStatus()} — one status and its application count. */
    interface StatusCount {
        ApplicationStatus getStatus();

        Long getCount();
    }
}
