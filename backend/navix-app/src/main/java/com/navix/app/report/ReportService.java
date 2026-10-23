package com.navix.app.report;

import com.navix.app.report.ReportDtos.Column;
import com.navix.app.report.ReportDtos.ColumnType;
import com.navix.app.report.ReportDtos.Fact;
import com.navix.app.report.ReportDtos.Report;
import com.navix.app.report.ReportDtos.ReportDef;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.entity.Loan;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.service.RepaymentService;
import com.navix.loan.service.RepaymentService.OutstandingBreakdown;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ADMIN reports centre: a catalog of downloadable reports about customers, loans, repayments,
 * collections, staff and the company's own money, each produced as one typed table (see
 * {@link ReportDtos}) for an optional IST calendar period.
 *
 * <p>Every report is read-only SQL over the live tables, except that what a loan <em>owes</em> —
 * interest, penalty, outstanding, and therefore whether it was overpaid — is priced through
 * {@link RepaymentService}, the one formula the rest of the product uses. It is never re-derived here,
 * so a report can't disagree with the loan page or the dashboard.
 *
 * <p>Deliberately ADMIN only (the real JWT role, not the working role). A report is built to be handed
 * on, so it carries the PAN masked and never the Aadhaar number or a bank account number.
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Rows a single report returns; beyond it the report says it was cut short. */
    static final int MAX_ROWS = 50_000;
    private static final int PRICING_CHUNK = 2000;
    private static final long MAX_SPAN_DAYS = 3660;

    /** PAN as {@code ABXXX1234X} — enough to recognise a borrower, not enough to reuse the number. */
    private static final String MASKED_PAN = "case when length(%1$s) = 10 then substr(%1$s, 1, 2) || 'XXX' || substr(%1$s, 6, 4) || 'X' end";

    /** The newest application that carries a profile, per customer: who the customer is today. */
    private static final String PROF_CTE = """
            prof as (
                select distinct on (a.customer_id) a.customer_id, p.full_name, p.mobile, p.pan, p.email,
                       p.employer, p.monthly_salary_paise
                from customer_profile p
                join loan_application a on a.id = p.application_id
                order by a.customer_id, a.id desc
            )""";

    private static final String OPEN_LOAN = "l.status in ('ACTIVE', 'OVERDUE', 'IN_COLLECTIONS', 'DISBURSING')";

    // ---- catalog ------------------------------------------------------------------------------

    static final String OVERVIEW = "Platform overview";
    static final String CUSTOMERS = "Customers";
    static final String LOANS = "Loans & repayments";
    static final String APPLICATIONS = "Applications & credit";
    static final String COLLECTIONS = "Collections";
    static final String STAFF = "Staff & employees";
    static final String FINANCE = "Finance";
    static final String GROWTH = "Growth & partners";

    static final List<ReportDef> CATALOG = List.of(
            new ReportDef("platform-summary", OVERVIEW, "Platform summary",
                    "Headline numbers for the period — applications, sanctions, disbursals, collections, "
                            + "income, expenses — plus today's live book.", "Event date"),
            new ReportDef("platform-guide", OVERVIEW, "How DhanBoost works",
                    "A shareable explainer: the loan product rules, the application lifecycle with today's "
                            + "count at each stage, and who does what on the staff side.", null),
            new ReportDef("monthly-performance", OVERVIEW, "Month-by-month performance",
                    "Per calendar month: loans disbursed, principal, fee and GST taken, money collected, "
                            + "company expenses.", "Month"),

            new ReportDef("customers", CUSTOMERS, "All customers",
                    "Every customer with contact, employer, salary, current status, loans taken and money "
                            + "borrowed / repaid.", "Sign-up date"),
            new ReportDef("customers-overpaid", CUSTOMERS, "Customers who overpaid",
                    "Loans where verified repayments exceed what was owed (principal + interest + penalty) — "
                            + "the excess is owed back to the customer.", "Disbursal date"),
            new ReportDef("customers-not-overpaid", CUSTOMERS, "Customers who have not overpaid",
                    "Every other loan: paid exactly, settled, partly paid or unpaid, with what is still "
                            + "outstanding.", "Disbursal date"),
            new ReportDef("customers-outstanding", CUSTOMERS, "Customers with dues outstanding",
                    "Open loans that still owe money today, with the amount and days past due.", null),
            new ReportDef("customers-repeat", CUSTOMERS, "Repeat customers",
                    "Customers who have taken more than one loan, with their history in one line.", null),
            new ReportDef("customer-transactions", CUSTOMERS, "Customer past transactions",
                    "Every money movement with a customer: disbursals out and repayments in (including "
                            + "pending and rejected ones).", "Transaction date"),

            new ReportDef("loan-book", LOANS, "Loan book & positions",
                    "Every disbursed loan priced as of today: interest, penalty, paid, outstanding, excess "
                            + "and days past due.", "Disbursal date"),
            new ReportDef("disbursals", LOANS, "Disbursals",
                    "Loans paid out: principal, processing fee, GST, net transferred, due date and the "
                            + "transfer reference.", "Disbursal date"),
            new ReportDef("repayments", LOANS, "Repayments",
                    "Every repayment recorded — method, status (verified / pending / rejected), who decided "
                            + "it and why.", "Payment date"),
            new ReportDef("overdue", LOANS, "Overdue loans",
                    "Loans past their due date today, by DPD bucket and collection officer.", null),
            new ReportDef("closed-loans", LOANS, "Closed loans",
                    "Loans fully repaid or settled, with what was paid against them.", "Closure date"),
            new ReportDef("nach-refunds", LOANS, "NACH refunds owed",
                    "Auto-debits that landed after the loan was already settled — money to return.", "Debit date"),

            new ReportDef("applications", APPLICATIONS, "Applications",
                    "Every application with status, amounts, the credit officer it sits with and the "
                            + "sanction decision.", "Created date"),
            new ReportDef("application-funnel", APPLICATIONS, "Application funnel",
                    "Applications started in the period, counted by where they stand now.", "Created date"),
            new ReportDef("rejections", APPLICATIONS, "Rejections",
                    "The rejection register: reason, manual or automatic, and how long the customer is "
                            + "blocked.", "Rejection date"),

            new ReportDef("collection-cases", COLLECTIONS, "Collection cases",
                    "Every collection case with its bucket, assigned officer and the loan's position.", "Opened date"),
            new ReportDef("settlements", COLLECTIONS, "Settlements",
                    "Settlement proposals with amount, status, proposer and approver.", "Proposed date"),

            new ReportDef("staff", STAFF, "Staff directory",
                    "Every staff account: role, status, department, designation, contact.", null),
            new ReportDef("staff-activity", STAFF, "Staff activity",
                    "What each staff member did in the period — every lifecycle action and every "
                            + "repayment decision, counted.", "Action date"),

            new ReportDef("expenses", FINANCE, "Company expenses",
                    "Expenses recorded by admin: what, to whom, how much.", "Expense date"),
            new ReportDef("fee-income", FINANCE, "Fee & GST statement",
                    "Per disbursed loan: processing fee earned and GST collected on it.", "Disbursal date"),

            new ReportDef("leads", GROWTH, "Leads",
                    "Leads from telecalling, imports and DSAs with call status and outcome.", "Created date"),
            new ReportDef("dsa-commissions", GROWTH, "DSA commissions",
                    "Commission accrued to each DSA, payable and paid.", "Accrued date"),
            new ReportDef("referral-payouts", GROWTH, "Referral payouts",
                    "Referral rewards owed and paid to customers.", "Created date"),
            new ReportDef("blocklist", GROWTH, "Blocklist",
                    "Blocked PANs, mobiles, emails and other identifiers with the reason.", "Added date"));

    private final NamedParameterJdbcTemplate jdbc;
    private final LoanRepository loanRepository;
    private final RepaymentService repayments;

    public ReportService(JdbcTemplate jdbcTemplate, LoanRepository loanRepository, RepaymentService repayments) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.loanRepository = loanRepository;
        this.repayments = repayments;
    }

    public List<ReportDef> catalog() {
        requireAdmin();
        return CATALOG;
    }

    /** Generate one report. {@code from}/{@code to} are an inclusive IST period; both null = all time. */
    public Report run(String key, LocalDate from, LocalDate to) {
        requireAdmin();
        ReportDef def = CATALOG.stream().filter(d -> d.key().equals(key)).findFirst()
                .orElseThrow(() -> new BusinessException("REPORT_NOT_FOUND", "No report named " + key));
        Period p = def.dateBasis() == null ? Period.ALL : Period.of(from, to);
        Table t = switch (key) {
            case "platform-summary" -> platformSummary(p);
            case "platform-guide" -> platformGuide();
            case "monthly-performance" -> monthlyPerformance(p);
            case "customers" -> customers(p);
            case "customers-overpaid" -> loanPositions(p, r -> r.excess > 0, false);
            case "customers-not-overpaid" -> loanPositions(p, r -> r.excess == 0, false);
            case "customers-outstanding" -> loanPositions(p, r -> r.open && r.outstanding > 0, true);
            case "customers-repeat" -> repeatCustomers();
            case "customer-transactions" -> transactions(p);
            case "loan-book" -> loanPositions(p, r -> true, false);
            case "disbursals" -> disbursals(p);
            case "repayments" -> repaymentsReport(p);
            case "overdue" -> loanPositions(p, r -> r.open && r.dpd > 0 && r.outstanding > 0, true);
            case "closed-loans" -> closedLoans(p);
            case "nach-refunds" -> nachRefunds(p);
            case "applications" -> applications(p);
            case "application-funnel" -> funnel(p);
            case "rejections" -> rejections(p);
            case "collection-cases" -> collectionCases(p);
            case "settlements" -> settlements(p);
            case "staff" -> staff();
            case "staff-activity" -> staffActivity(p);
            case "expenses" -> expenses(p);
            case "fee-income" -> feeIncome(p);
            case "leads" -> leads(p);
            case "dsa-commissions" -> dsaCommissions(p);
            case "referral-payouts" -> referralPayouts(p);
            case "blocklist" -> blocklist(p);
            default -> throw new BusinessException("REPORT_NOT_FOUND", "No report named " + key);
        };
        boolean truncated = t.rows.size() > MAX_ROWS;
        List<List<Object>> rows = truncated ? t.rows.subList(0, MAX_ROWS) : t.rows;
        List<String> notes = new ArrayList<>(t.notes);
        if (truncated) {
            notes.add("Only the first " + MAX_ROWS + " rows are included — narrow the period for the rest.");
        }
        return new Report(def.key(), def.category(), def.title(), def.description(), def.dateBasis(),
                p.from == null ? null : p.from.toString(), p.to == null ? null : p.to.toString(),
                Instant.now().toString(), t.columns, rows, t.summary, notes, truncated, t.totals);
    }

    // ---- overview -----------------------------------------------------------------------------

    private Table platformSummary(Period p) {
        List<Column> cols = List.of(Column.text("area", "Area"), Column.text("metric", "Metric"),
                Column.number("count", "Count"), Column.money("amount", "Amount (Rs.)"),
                Column.text("basis", "Basis"));
        List<List<Object>> rows = new ArrayList<>();
        String inPeriod = p.all() ? "All time" : "In period";
        MapSqlParameterSource params = p.params();

        long[] v = pair("select count(*), 0 from loan_application a where " + p.on(ist("a.created_at")), params);
        rows.add(row("Applications", "Applications started", v[0], null, inPeriod));
        v = pair("select count(*), 0 from (select customer_id, min(created_at) c from loan_application group by customer_id) f"
                + " where " + p.on(ist("f.c")), params);
        rows.add(row("Customers", "New customers signed up", v[0], null, inPeriod));
        v = pair("select count(*), coalesce(sum(a.sanctioned_amount_paise), 0) from loan_application a"
                + " where a.sanctioned_at is not null and " + p.on(ist("a.sanctioned_at")), params);
        rows.add(row("Applications", "Applications sanctioned", v[0], v[1], inPeriod));
        v = pair("select count(*), 0 from application_rejection r where " + p.on(ist("r.created_at")), params);
        rows.add(row("Applications", "Applications rejected", v[0], null, inPeriod));

        String disb = "from loan l where l.disbursed_on is not null and " + p.on("l.disbursed_on");
        v = pair("select count(*), coalesce(sum(l.principal), 0) " + disb, params);
        rows.add(row("Disbursals", "Loans disbursed (principal)", v[0], v[1], inPeriod));
        v = pair("select count(*), coalesce(sum(l.net_disbursed), 0) " + disb, params);
        rows.add(row("Disbursals", "Net amount transferred to customers", v[0], v[1], inPeriod));
        v = pair("select count(*), coalesce(sum(l.processing_fee), 0) " + disb, params);
        rows.add(row("Income", "Processing fee earned", v[0], v[1], inPeriod));
        v = pair("select count(*), coalesce(sum(l.gst), 0) " + disb, params);
        rows.add(row("Income", "GST collected on fees", v[0], v[1], inPeriod));
        v = pair("select count(*), 0 " + disb + " and exists (select 1 from loan e where e.customer_id = l.customer_id"
                + " and e.id < l.id and e.disbursed_on is not null)", params);
        rows.add(row("Disbursals", "Of which repeat loans", v[0], null, inPeriod));

        v = pair("select count(*), coalesce(sum(p.amount), 0) from payment p where p.status = 'VERIFIED' and "
                + p.on("p.paid_on"), params);
        rows.add(row("Collections", "Repayments verified", v[0], v[1], inPeriod));
        v = pair("select count(*), coalesce(sum(p.amount), 0) from payment p where p.status = 'REJECTED' and "
                + p.on("p.paid_on"), params);
        rows.add(row("Collections", "Repayments rejected", v[0], v[1], inPeriod));
        v = pair("select count(*), 0 from loan l where l.closed_on is not null and " + p.on("l.closed_on"), params);
        rows.add(row("Collections", "Loans closed", v[0], null, inPeriod));
        v = pair("select count(*), coalesce(sum(s.settlement_amount), 0) from settlement s where s.status = 'APPROVED' and "
                + p.on(ist("s.approved_at")), params);
        rows.add(row("Collections", "Settlements approved", v[0], v[1], inPeriod));

        v = pair("select count(*), coalesce(sum(x.amount_paise), 0) from company_expense x where " + p.on("x.expense_date"), params);
        rows.add(row("Expenses", "Company expenses", v[0], v[1], inPeriod));
        v = pair("select count(*), coalesce(sum(c.amount_paise), 0) from dsa_commission c where "
                + p.on(ist("c.accrued_at")), params);
        rows.add(row("Expenses", "DSA commission accrued", v[0], v[1], inPeriod));
        v = pair("select count(*), coalesce(sum(r.amount_paise), 0) from referral_payout r where r.status = 'PAID' and "
                + p.on(ist("r.paid_at")), params);
        rows.add(row("Expenses", "Referral rewards paid", v[0], v[1], inPeriod));
        v = pair("select count(*), 0 from lead x where " + p.on(ist("x.created_at")), params);
        rows.add(row("Growth", "Leads created", v[0], null, inPeriod));

        // Today's book, priced the same way as the loan page.
        List<Position> open = positions(Period.ALL, OPEN_LOAN);
        long outstanding = 0, overdueN = 0, overdueAmt = 0;
        for (Position r : open) {
            outstanding += r.outstanding;
            if (r.dpd > 0 && r.outstanding > 0) {
                overdueN++;
                overdueAmt += r.outstanding;
            }
        }
        rows.add(row("Live book", "Open loans — outstanding", (long) open.size(), outstanding, "As of today"));
        rows.add(row("Live book", "Overdue loans — outstanding", overdueN, overdueAmt, "As of today"));
        v = pair("select count(*), coalesce(sum(p.amount), 0) from payment p where p.status = 'PENDING_VERIFICATION'", params);
        rows.add(row("Live book", "Repayments awaiting verification", v[0], v[1], "As of today"));
        List<Position> all = positions(Period.ALL, "true");
        long overpaidN = all.stream().filter(r -> r.excess > 0).count();
        long overpaid = all.stream().mapToLong(r -> r.excess).sum();
        rows.add(row("Live book", "Loans overpaid — excess owed back", overpaidN, overpaid, "As of today"));
        v = pair("select count(*), 0 from staff_user s where s.status = 'ACTIVE'", params);
        rows.add(row("Staff", "Active staff accounts", v[0], null, "As of today"));

        List<Fact> facts = List.of(
                new Fact("Loans disbursed", rows.get(4).get(2), ColumnType.NUMBER),
                new Fact("Principal disbursed", rows.get(4).get(3), ColumnType.MONEY),
                new Fact("Repayments verified", rows.get(9).get(3), ColumnType.MONEY),
                new Fact("Outstanding today", outstanding, ColumnType.MONEY));
        return new Table(cols, rows, facts, List.of(
                "\"In period\" rows count events whose date falls in the selected period; \"As of today\" rows are a "
                        + "live snapshot regardless of the period.",
                "GST is collected on behalf of the government and is not income."), false);
    }

    private Table platformGuide() {
        List<Column> cols = List.of(Column.text("section", "Section"), Column.text("item", "Item"),
                Column.text("detail", "How it works"), Column.number("live", "Live count"));
        List<List<Object>> rows = new ArrayList<>();
        String p = "Product";
        rows.add(row(p, "What it is", "A salary-linked short advance. A salaried customer borrows against the next "
                + "salary and repays it in a single payment on or after salary day.", null));
        rows.add(row(p, "Eligible limit", "25% of monthly salary, rounded down to the nearest Rs. 100. Minimum loan Rs. 1,000.", null));
        rows.add(row(p, "Processing fee", "10% of the principal, taken upfront from the amount disbursed.", null));
        rows.add(row(p, "GST", "18% on the processing fee, also taken upfront.", null));
        rows.add(row(p, "Amount received", "Principal minus fee minus GST. Example: Rs. 10,000 loan -> Rs. 8,820 received.", null));
        rows.add(row(p, "Interest", "1% of principal per day for the days the money is held.", null));
        rows.add(row(p, "Due date", "The customer's next salary day, no later than 40 days after disbursal.", null));
        rows.add(row(p, "Repayment", "One payment of principal + interest. Paying early only charges interest to the day paid.", null));
        rows.add(row(p, "Late penalty", "One grace day after the due date, then 2% of principal per day, capped at 30 days.", null));
        rows.add(row(p, "Repeat loans", "A customer who repaid on time can borrow again with a shortened journey; "
                + "KYC and details are carried over.", null));

        String l = "Lifecycle";
        Map<String, Long> byStatus = new HashMap<>();
        jdbc.query("select status, count(*) n from loan_application group by status", Map.of(),
                rs -> { byStatus.put(rs.getString("status"), rs.getLong("n")); });
        String[][] stages = {
                {"DRAFT", "Sign-up: mobile OTP, employment, employer, email, bank, payslips, Aadhaar and PAN cards, consent."},
                {"KYC_PENDING", "Submitted; waiting for the credit team to pick the file up."},
                {"CREDIT_EXEC_PENDING", "With a credit executive, who sanctions, rejects or parks the file for a document."},
                {"SANCTIONED", "Approved amount and date; the customer completes the offer journey and eSigns the agreement."},
                {"DISBURSEMENT_PENDING", "Agreement signed; waiting for the disbursement head to transfer the money."},
                {"ACTIVE", "Money sent; loan running until salary day."},
                {"OVERDUE", "Past due date and unpaid; with collections."},
                {"CLOSED", "Fully repaid (or settled)."},
                {"REJECTED", "Turned down; the customer is blocked from re-applying for a cooling-off period."},
                {"CANCELLED", "Withdrawn before disbursal."},
        };
        for (String[] s : stages) {
            rows.add(row(l, s[0].replace('_', ' '), s[1], byStatus.getOrDefault(s[0], 0L)));
        }

        String r = "Roles";
        Map<String, Long> byRole = new HashMap<>();
        jdbc.query("select role, count(*) n from staff_user where status = 'ACTIVE' group by role", Map.of(),
                rs -> { byRole.put(rs.getString("role"), rs.getLong("n")); });
        String[][] roles = {
                {"ADMIN", "Oversight of the whole platform: staff, settings, corrections, reports."},
                {"CREDIT_HEAD", "Assigns credit files to executives; may reject leads."},
                {"CREDIT_EXECUTIVE", "Makes the credit decision: sanction, reject or park."},
                {"DISBURSEMENT_HEAD", "Transfers the money and records the transfer reference; pays referral rewards."},
                {"ACCOUNTANT", "Verifies or rejects repayments customers report; keeps the transactions ledger."},
                {"COLLECTION_HEAD", "Runs collections: assigns cases, approves settlements and payments."},
                {"COLLECTION_EXECUTIVE", "Calls overdue customers, logs interactions, records payments, proposes settlements."},
                {"TELECALLER", "Calls leads and logs outcomes; no say in loan decisions."},
                {"DSA", "External agent who brings leads and earns 3.5% of net disbursed on a lead's first repaid loan."},
        };
        for (String[] s : roles) {
            rows.add(row(r, s[0].replace('_', ' '), s[1], byRole.getOrDefault(s[0], 0L)));
        }

        String c = "Controls";
        rows.add(row(c, "Maker-checker", "No one approves their own work: proposer and approver of a settlement, "
                + "or a repayment and its verifier, must be different people.", null));
        rows.add(row(c, "Audit trail", "Every status change records who did it, in which role, and when.", null));
        rows.add(row(c, "Verification", "PAN, Aadhaar (DigiLocker), bank account (penny drop), employment and credit "
                + "bureau are checked with external providers; a failed check goes to a person, never an automatic decline.", null));
        rows.add(row(c, "Money", "All amounts are kept in paise and rounded half-up; a repayment only counts once "
                + "verified.", null));

        long customers = scalar("select count(distinct customer_id) from loan_application");
        long loans = scalar("select count(*) from loan where disbursed_on is not null");
        List<Fact> facts = List.of(new Fact("Customers", customers, ColumnType.NUMBER),
                new Fact("Loans disbursed (all time)", loans, ColumnType.NUMBER),
                new Fact("Active staff", byRole.values().stream().mapToLong(Long::longValue).sum(), ColumnType.NUMBER));
        return new Table(cols, rows, facts, List.of("Live counts are as of the moment the report was generated."));
    }

    private Table monthlyPerformance(Period p) {
        List<Column> cols = List.of(Column.text("month", "Month"), Column.number("loans", "Loans disbursed"),
                Column.money("principal", "Principal (Rs.)"), Column.money("fee", "Processing fee (Rs.)"),
                Column.money("gst", "GST (Rs.)"), Column.money("net", "Net disbursed (Rs.)"),
                Column.number("payments", "Repayments verified"), Column.money("collected", "Collected (Rs.)"),
                Column.number("closed", "Loans closed"), Column.money("expenses", "Expenses (Rs.)"));
        String sql = """
                with d as (select to_char(l.disbursed_on, 'YYYY-MM') m, count(*) n, sum(l.principal) principal,
                                  sum(coalesce(l.processing_fee, 0)) fee, sum(coalesce(l.gst, 0)) gst,
                                  sum(coalesce(l.net_disbursed, 0)) net
                           from loan l where l.disbursed_on is not null and %1$s group by 1),
                     c as (select to_char(p.paid_on, 'YYYY-MM') m, count(*) n, sum(p.amount) amt
                           from payment p where p.status = 'VERIFIED' and %2$s group by 1),
                     k as (select to_char(l.closed_on, 'YYYY-MM') m, count(*) n
                           from loan l where l.closed_on is not null and %3$s group by 1),
                     x as (select to_char(e.expense_date, 'YYYY-MM') m, sum(e.amount_paise) amt
                           from company_expense e where %4$s group by 1),
                     months as (select m from d union select m from c union select m from k union select m from x)
                select months.m, coalesce(d.n, 0), coalesce(d.principal, 0), coalesce(d.fee, 0), coalesce(d.gst, 0),
                       coalesce(d.net, 0), coalesce(c.n, 0), coalesce(c.amt, 0), coalesce(k.n, 0), coalesce(x.amt, 0)
                from months
                left join d on d.m = months.m left join c on c.m = months.m
                left join k on k.m = months.m left join x on x.m = months.m
                where months.m is not null
                order by months.m desc
                """.formatted(p.on("l.disbursed_on"), p.on("p.paid_on"), p.on("l.closed_on"), p.on("e.expense_date"));
        return totalled(cols, query(sql, p.params()), List.of());
    }

    // ---- customers ----------------------------------------------------------------------------

    private Table customers(Period p) {
        List<Column> cols = List.of(Column.id("customerId", "Customer ID"), Column.text("name", "Name"),
                Column.text("mobile", "Mobile"), Column.text("pan", "PAN (masked)"), Column.text("email", "Email"),
                Column.text("employer", "Employer"), Column.money("salary", "Monthly salary (Rs.)"),
                Column.dateTime("signedUp", "Signed up"), Column.text("status", "Latest application status"),
                Column.number("applications", "Applications"), Column.number("loans", "Loans taken"),
                Column.number("closed", "Loans closed"), Column.money("borrowed", "Total borrowed (Rs.)"),
                Column.money("repaid", "Total repaid (Rs.)"), Column.text("owner", "Relationship owner"));
        String sql = "with " + PROF_CTE + """
                , f as (select customer_id, min(created_at) signed_up, max(id) latest_app, count(*) apps
                        from loan_application group by customer_id),
                  lo as (select customer_id, count(*) n, count(*) filter (where status = 'CLOSED') closed,
                                sum(principal) borrowed
                         from loan where disbursed_on is not null group by customer_id),
                  pd as (select l.customer_id, sum(p.amount) repaid from payment p join loan l on l.id = p.loan_id
                         where p.status = 'VERIFIED' group by l.customer_id)
                select f.customer_id, pr.full_name, pr.mobile, %s, pr.email, pr.employer, pr.monthly_salary_paise,
                       f.signed_up, la.status, f.apps, coalesce(lo.n, 0), coalesce(lo.closed, 0),
                       coalesce(lo.borrowed, 0), coalesce(pd.repaid, 0), su.name
                from f
                left join prof pr on pr.customer_id = f.customer_id
                left join loan_application la on la.id = f.latest_app
                left join lo on lo.customer_id = f.customer_id
                left join pd on pd.customer_id = f.customer_id
                left join customer_owner o on o.customer_id = f.customer_id
                left join staff_user su on su.id = o.owner_staff_id
                where %s
                order by f.signed_up desc
                """.formatted(MASKED_PAN.formatted("pr.pan"), p.on(ist("f.signed_up")));
        List<List<Object>> rows = query(sql, p.params());
        long withLoans = rows.stream().filter(r -> ((Number) r.get(10)).longValue() > 0).count();
        return totalled(cols, rows, List.of(new Fact("Customers", (long) rows.size(), ColumnType.NUMBER),
                new Fact("With at least one loan", withLoans, ColumnType.NUMBER)));
    }

    private Table repeatCustomers() {
        List<Column> cols = List.of(Column.id("customerId", "Customer ID"), Column.text("name", "Name"),
                Column.text("mobile", "Mobile"), Column.number("loans", "Loans taken"),
                Column.number("closed", "Loans closed"), Column.date("first", "First loan"),
                Column.date("last", "Latest loan"), Column.money("borrowed", "Total borrowed (Rs.)"),
                Column.money("repaid", "Total repaid (Rs.)"));
        String sql = "with " + PROF_CTE + """
                , lo as (select customer_id, count(*) n, count(*) filter (where status = 'CLOSED') closed,
                                min(disbursed_on) first, max(disbursed_on) last, sum(principal) borrowed
                         from loan where disbursed_on is not null group by customer_id having count(*) > 1),
                  pd as (select l.customer_id, sum(p.amount) repaid from payment p join loan l on l.id = p.loan_id
                         where p.status = 'VERIFIED' group by l.customer_id)
                select lo.customer_id, pr.full_name, pr.mobile, lo.n, lo.closed, lo.first, lo.last, lo.borrowed,
                       coalesce(pd.repaid, 0)
                from lo left join prof pr on pr.customer_id = lo.customer_id
                left join pd on pd.customer_id = lo.customer_id
                order by lo.n desc, lo.last desc
                """;
        List<List<Object>> rows = query(sql, new MapSqlParameterSource());
        return totalled(cols, rows, List.of(new Fact("Repeat customers", (long) rows.size(), ColumnType.NUMBER)));
    }

    private Table transactions(Period p) {
        List<Column> cols = List.of(Column.date("date", "Date"), Column.text("type", "Type"),
                Column.text("direction", "Direction"), Column.id("customerId", "Customer ID"),
                Column.text("name", "Customer"), Column.text("mobile", "Mobile"), Column.id("loanId", "Loan ID"),
                Column.money("amount", "Amount (Rs.)"), Column.text("method", "Method"),
                Column.text("status", "Status"), Column.text("reference", "Reference"));
        String sql = "with " + PROF_CTE + """
                , t as (
                    select l.disbursed_on d, 'Disbursal' type, 'Out' dir, l.customer_id, l.id loan_id,
                           coalesce(l.net_disbursed, 0) amount, 'Bank transfer' method, cast(l.status as text) status,
                           l.disbursal_txn_ref ref, 0 ord, l.id rid
                    from loan l where l.disbursed_on is not null
                    union all
                    select p.paid_on, 'Repayment', 'In', l.customer_id, p.loan_id, p.amount, cast(p.method as text),
                           cast(p.status as text), p.txn_ref, 1, p.id
                    from payment p join loan l on l.id = p.loan_id
                )
                select t.d, t.type, t.dir, t.customer_id, pr.full_name, pr.mobile, t.loan_id, t.amount, t.method,
                       t.status, t.ref
                from t left join prof pr on pr.customer_id = t.customer_id
                where %s
                order by t.d desc nulls last, t.ord, t.rid desc
                """.formatted(p.on("t.d"));
        List<List<Object>> rows = query(sql, p.params());
        long out = 0, in = 0;
        for (List<Object> r : rows) {
            long amt = ((Number) r.get(7)).longValue();
            if ("Out".equals(r.get(2))) out += amt;
            else if ("VERIFIED".equals(r.get(9))) in += amt;
        }
        return new Table(cols, rows, List.of(new Fact("Transactions", (long) rows.size(), ColumnType.NUMBER),
                new Fact("Disbursed (out)", out, ColumnType.MONEY), new Fact("Repaid, verified (in)", in, ColumnType.MONEY)),
                List.of("Repayments in every status are listed; only VERIFIED repayments count towards what a customer has paid."));
    }

    // ---- loans --------------------------------------------------------------------------------

    /** One priced loan: what it owed, what was paid, where it stands. */
    private static final class Position {
        long loanId, customerId, principal, interest, penalty, paid, outstanding, excess, dpd;
        String name, mobile, status, officer, position;
        LocalDate disbursedOn, dueDate, closedOn;
        boolean open;
    }

    /** Disbursed loans matching {@code where} (SQL over alias {@code l}), priced as of today. */
    private List<Position> positions(Period p, String where) {
        String sql = "with " + PROF_CTE + """
                , cc as (select distinct on (loan_id) loan_id, assigned_officer_id from collection_case
                         order by loan_id, created_at desc)
                select l.id, l.customer_id, pr.full_name, pr.mobile, cast(l.status as text) status, l.disbursed_on,
                       l.due_date, l.closed_on, l.principal, su.name officer
                from loan l
                left join prof pr on pr.customer_id = l.customer_id
                left join cc on cc.loan_id = l.id
                left join staff_user su on su.id = cc.assigned_officer_id
                where l.disbursed_on is not null and %s and %s
                order by l.disbursed_on desc, l.id desc
                limit %d
                """.formatted(where, p.on("l.disbursed_on"), MAX_ROWS + 1);
        List<Position> out = new ArrayList<>();
        jdbc.query(sql, p.params(), rs -> {
            Position r = new Position();
            r.loanId = rs.getLong(1);
            r.customerId = rs.getLong(2);
            r.name = rs.getString(3);
            r.mobile = rs.getString(4);
            r.status = rs.getString(5);
            r.disbursedOn = localDate(rs, 6);
            r.dueDate = localDate(rs, 7);
            r.closedOn = localDate(rs, 8);
            r.principal = rs.getLong(9);
            r.officer = rs.getString(10);
            out.add(r);
        });
        Map<Long, OutstandingBreakdown> priced = price(out.stream().map(r -> r.loanId).toList());
        LocalDate today = LocalDate.now(IST);
        for (Position r : out) {
            OutstandingBreakdown b = priced.get(r.loanId);
            boolean settled = b != null && b.settledAmountPaise() != null;
            if (b != null) {
                r.interest = b.interestPaise();
                r.penalty = b.penaltyPaise();
                r.paid = b.verifiedPaise();
                r.outstanding = b.outstandingPaise();
                // Outstanding floors at zero, so money paid beyond what was owed is its own figure —
                // the same rule the dashboard's position card uses. A settled loan was owed less by
                // agreement, so it is never "overpaid".
                r.excess = settled ? 0 : Math.max(0, r.paid - r.principal - r.interest - r.penalty);
            }
            r.open = r.closedOn == null && !"CLOSED".equals(r.status) && !"REPAID".equals(r.status);
            if ("ACTIVE".equals(r.status) && r.dueDate != null && r.dueDate.isBefore(today)) {
                r.status = "OVERDUE";
            }
            r.dpd = r.open && r.outstanding > 0 && r.dueDate != null
                    ? Math.max(0, ChronoUnit.DAYS.between(r.dueDate, today)) : 0;
            r.position = r.excess > 0 ? "Overpaid"
                    : settled ? "Settled"
                    : r.outstanding == 0 ? "Paid in full"
                    : r.paid > 0 ? "Partly paid" : "Unpaid";
        }
        return out;
    }

    private Table loanPositions(Period p, Predicate<Position> keep, boolean withCollections) {
        List<Column> cols = new ArrayList<>(List.of(Column.id("loanId", "Loan ID"),
                Column.id("customerId", "Customer ID"), Column.text("name", "Customer"),
                Column.text("mobile", "Mobile"), Column.text("status", "Loan status"),
                Column.date("disbursedOn", "Disbursed on"), Column.date("dueDate", "Due date"),
                Column.date("closedOn", "Closed on"), Column.money("principal", "Principal (Rs.)"),
                Column.money("interest", "Interest (Rs.)"), Column.money("penalty", "Penalty (Rs.)"),
                Column.money("owed", "Total owed (Rs.)"), Column.money("paid", "Paid, verified (Rs.)"),
                Column.money("outstanding", "Outstanding (Rs.)"), Column.money("excess", "Excess paid (Rs.)"),
                Column.text("position", "Position"), Column.number("dpd", "Days past due")));
        if (withCollections) {
            cols.add(Column.text("bucket", "DPD bucket"));
            cols.add(Column.text("officer", "Collection officer"));
        }
        List<Position> all = positions(p, "true");
        List<List<Object>> rows = new ArrayList<>();
        long outstanding = 0, excess = 0;
        for (Position r : all) {
            if (!keep.test(r)) continue;
            outstanding += r.outstanding;
            excess += r.excess;
            List<Object> row = new ArrayList<>(List.of(r.loanId, r.customerId));
            row.add(r.name);
            row.add(r.mobile);
            row.add(r.status);
            row.add(iso(r.disbursedOn));
            row.add(iso(r.dueDate));
            row.add(iso(r.closedOn));
            row.addAll(List.of(r.principal, r.interest, r.penalty, r.principal + r.interest + r.penalty, r.paid,
                    r.outstanding, r.excess));
            row.add(r.position);
            row.add(r.dpd);
            if (withCollections) {
                row.add(bucket(r.dpd));
                row.add(r.officer);
            }
            rows.add(row);
        }
        List<Fact> facts = new ArrayList<>(List.of(new Fact("Loans", (long) rows.size(), ColumnType.NUMBER),
                new Fact("Outstanding", outstanding, ColumnType.MONEY)));
        if (excess > 0) facts.add(new Fact("Excess to refund", excess, ColumnType.MONEY));
        return totalledWith(cols, rows, facts, List.of(
                "Interest, penalty and outstanding are priced as of today with the same formula as the loan page.",
                "\"Excess paid\" is verified money beyond principal + interest + penalty; it is owed back to the customer."),
                Map.of());
    }

    private Table disbursals(Period p) {
        List<Column> cols = List.of(Column.id("loanId", "Loan ID"), Column.id("applicationId", "Application ID"),
                Column.id("customerId", "Customer ID"), Column.text("name", "Customer"), Column.text("mobile", "Mobile"),
                Column.date("disbursedOn", "Disbursed on"), Column.date("dueDate", "Due date"),
                Column.money("principal", "Principal (Rs.)"), Column.money("fee", "Processing fee (Rs.)"),
                Column.money("gst", "GST (Rs.)"), Column.money("net", "Net disbursed (Rs.)"),
                Column.money("repayable", "Total repayable (Rs.)"), Column.text("status", "Status"),
                Column.text("txnRef", "Transfer reference"), Column.text("repeat", "First / repeat"));
        String sql = "with " + PROF_CTE + """
                , app as (select distinct on (loan_id) loan_id, id from loan_application where loan_id is not null
                          order by loan_id, id desc)
                select l.id, app.id, l.customer_id, pr.full_name, pr.mobile, l.disbursed_on, l.due_date, l.principal,
                       coalesce(l.processing_fee, 0), coalesce(l.gst, 0), coalesce(l.net_disbursed, 0),
                       coalesce(l.total_repayable, 0), cast(l.status as text), l.disbursal_txn_ref,
                       case when exists (select 1 from loan e where e.customer_id = l.customer_id and e.id < l.id
                                         and e.disbursed_on is not null) then 'Repeat' else 'First' end
                from loan l
                left join prof pr on pr.customer_id = l.customer_id
                left join app on app.loan_id = l.id
                where l.disbursed_on is not null and %s
                order by l.disbursed_on desc, l.id desc
                """.formatted(p.on("l.disbursed_on"));
        List<List<Object>> rows = query(sql, p.params());
        return totalled(cols, rows, List.of(new Fact("Loans disbursed", (long) rows.size(), ColumnType.NUMBER),
                new Fact("Principal", sum(rows, 7), ColumnType.MONEY), new Fact("Net transferred", sum(rows, 10), ColumnType.MONEY)));
    }

    private Table repaymentsReport(Period p) {
        List<Column> cols = List.of(Column.id("paymentId", "Payment ID"), Column.id("loanId", "Loan ID"),
                Column.id("customerId", "Customer ID"), Column.text("name", "Customer"), Column.text("mobile", "Mobile"),
                Column.date("paidOn", "Paid on"), Column.money("amount", "Amount (Rs.)"), Column.text("method", "Method"),
                Column.text("status", "Status"), Column.bool("partial", "Part payment"), Column.text("txnRef", "Reference"),
                Column.text("decidedBy", "Decided by"), Column.dateTime("decidedAt", "Decided at"),
                Column.text("rejection", "Rejection reason"), Column.dateTime("recordedAt", "Recorded at"));
        String sql = "with " + PROF_CTE + """
                select p.id, p.loan_id, l.customer_id, pr.full_name, pr.mobile, p.paid_on, p.amount,
                       cast(p.method as text), cast(p.status as text), p.partial, p.txn_ref, su.name, p.decided_at,
                       nullif(concat_ws(' - ', p.rejection_reason, p.rejection_note), ''), p.created_at
                from payment p
                join loan l on l.id = p.loan_id
                left join prof pr on pr.customer_id = l.customer_id
                left join staff_user su on su.id = p.decided_by
                where %s
                order by p.paid_on desc nulls last, p.id desc
                """.formatted(p.on("p.paid_on"));
        List<List<Object>> rows = query(sql, p.params());
        long verified = 0, pending = 0;
        for (List<Object> r : rows) {
            long amt = ((Number) r.get(6)).longValue();
            if ("VERIFIED".equals(r.get(8))) verified += amt;
            if ("PENDING_VERIFICATION".equals(r.get(8))) pending += amt;
        }
        return new Table(cols, rows, List.of(new Fact("Repayments", (long) rows.size(), ColumnType.NUMBER),
                new Fact("Verified", verified, ColumnType.MONEY), new Fact("Awaiting verification", pending, ColumnType.MONEY)),
                List.of());
    }

    private Table closedLoans(Period p) {
        List<Column> cols = List.of(Column.id("loanId", "Loan ID"), Column.id("customerId", "Customer ID"),
                Column.text("name", "Customer"), Column.text("mobile", "Mobile"), Column.date("disbursedOn", "Disbursed on"),
                Column.date("dueDate", "Due date"), Column.date("closedOn", "Closed on"),
                Column.number("lateDays", "Days late"), Column.money("principal", "Principal (Rs.)"),
                Column.money("paid", "Paid, verified (Rs.)"), Column.text("how", "Closed by"));
        String sql = "with " + PROF_CTE + """
                , pd as (select loan_id, sum(amount) amt from payment where status = 'VERIFIED' group by loan_id),
                  st as (select distinct cc.loan_id from settlement s join collection_case cc on cc.id = s.collection_case_id
                         where s.status = 'APPROVED')
                select l.id, l.customer_id, pr.full_name, pr.mobile, l.disbursed_on, l.due_date, l.closed_on,
                       greatest(0, l.closed_on - l.due_date), l.principal, coalesce(pd.amt, 0),
                       case when st.loan_id is not null then 'Settlement' else 'Full repayment' end
                from loan l
                left join prof pr on pr.customer_id = l.customer_id
                left join pd on pd.loan_id = l.id
                left join st on st.loan_id = l.id
                where l.closed_on is not null and %s
                order by l.closed_on desc, l.id desc
                """.formatted(p.on("l.closed_on"));
        List<List<Object>> rows = query(sql, p.params());
        long late = rows.stream().filter(r -> r.get(7) != null && ((Number) r.get(7)).longValue() > 1).count();
        return totalled(cols, rows, List.of(new Fact("Loans closed", (long) rows.size(), ColumnType.NUMBER),
                new Fact("Repaid after the grace day", late, ColumnType.NUMBER)));
    }

    private Table nachRefunds(Period p) {
        List<Column> cols = List.of(Column.id("debitId", "Debit ID"), Column.id("loanId", "Loan ID"),
                Column.id("customerId", "Customer ID"), Column.text("name", "Customer"), Column.text("mobile", "Mobile"),
                Column.money("amount", "Amount (Rs.)"), Column.text("status", "Debit status"), Column.text("utr", "UTR"),
                Column.date("settlementDate", "Settlement date"), Column.dateTime("createdAt", "Presented at"));
        String sql = "with " + PROF_CTE + """
                select d.id, d.loan_id, l.customer_id, pr.full_name, pr.mobile, d.amount_paise, d.status, d.utr,
                       d.settlement_date, d.created_at
                from mandate_debit d
                join loan l on l.id = d.loan_id
                left join prof pr on pr.customer_id = l.customer_id
                where d.overpayment and %s
                order by d.id desc
                """.formatted(p.on(ist("d.created_at")));
        List<List<Object>> rows = query(sql, p.params());
        return totalled(cols, rows, List.of(new Fact("Refunds owed", sum(rows, 5), ColumnType.MONEY)));
    }

    // ---- applications -------------------------------------------------------------------------

    private Table applications(Period p) {
        List<Column> cols = List.of(Column.id("applicationId", "Application ID"), Column.id("customerId", "Customer ID"),
                Column.text("name", "Customer"), Column.text("mobile", "Mobile"), Column.text("pan", "PAN (masked)"),
                Column.text("employer", "Employer"), Column.money("salary", "Monthly salary (Rs.)"),
                Column.dateTime("createdAt", "Created"), Column.text("status", "Status"),
                Column.text("journey", "Journey step"), Column.money("eligible", "Eligible limit (Rs.)"),
                Column.money("requested", "Requested (Rs.)"), Column.money("sanctioned", "Sanctioned (Rs.)"),
                Column.date("repaymentDate", "Repayment date"), Column.text("executive", "Credit executive"),
                Column.text("sanctionedBy", "Sanctioned by"), Column.dateTime("sanctionedAt", "Sanctioned at"),
                Column.text("pending", "Parked for"), Column.id("loanId", "Loan ID"),
                Column.text("kind", "Fresh / reborrow"));
        String sql = """
                select a.id, a.customer_id, p.full_name, p.mobile, %s, p.employer, p.monthly_salary_paise,
                       a.created_at, a.status, a.journey_step, a.eligible_limit, a.amount_requested,
                       a.sanctioned_amount_paise, a.approved_repayment_date, ex.name, sb.name, a.sanctioned_at,
                       a.pending_reason, a.loan_id,
                       case when a.reapplied_from is not null then 'Reborrow' else 'Fresh' end
                from loan_application a
                left join customer_profile p on p.application_id = a.id
                left join staff_user ex on ex.id = a.assigned_executive_id
                left join staff_user sb on sb.id = a.sanctioned_by
                where %s
                order by a.created_at desc, a.id desc
                """.formatted(MASKED_PAN.formatted("p.pan"), p.on(ist("a.created_at")));
        List<List<Object>> rows = query(sql, p.params());
        long sanctioned = rows.stream().filter(r -> r.get(16) != null).count();
        return totalled(cols, rows, List.of(new Fact("Applications", (long) rows.size(), ColumnType.NUMBER),
                new Fact("Sanctioned", sanctioned, ColumnType.NUMBER)));
    }

    private Table funnel(Period p) {
        List<Column> cols = List.of(Column.text("status", "Current status"), Column.number("count", "Applications"),
                Column.number("share", "Share (%)"), Column.money("requested", "Requested (Rs.)"),
                Column.money("sanctioned", "Sanctioned (Rs.)"));
        String sql = """
                with x as (select a.status, count(*) n, coalesce(sum(a.amount_requested), 0) req,
                                  coalesce(sum(a.sanctioned_amount_paise), 0) sanc
                           from loan_application a where %s group by a.status)
                select status, n, round(100.0 * n / nullif(sum(n) over (), 0), 1), req, sanc from x order by n desc
                """.formatted(p.on(ist("a.created_at")));
        List<List<Object>> rows = query(sql, p.params());
        return totalledWith(cols, rows, List.of(new Fact("Applications", sum(rows, 1), ColumnType.NUMBER)), List.of(),
                Map.of(2, true));
    }

    private Table rejections(Period p) {
        List<Column> cols = List.of(Column.id("id", "Rejection ID"), Column.id("applicationId", "Application ID"),
                Column.id("customerId", "Customer ID"), Column.text("name", "Customer"), Column.text("mobile", "Mobile"),
                Column.text("reason", "Reason"), Column.text("detail", "Detail"), Column.text("how", "Manual / automatic"),
                Column.text("by", "Rejected by"), Column.dateTime("at", "Rejected at"),
                Column.dateTime("blockedUntil", "Blocked until"));
        String sql = "with " + PROF_CTE + """
                select r.id, r.application_id, r.customer_id, pr.full_name, coalesce(r.mobile, pr.mobile), r.reason_code,
                       r.reason_detail, case when r.auto then 'Automatic' else 'Manual' end,
                       coalesce(su.name, r.created_by), r.created_at, r.blocked_until
                from application_rejection r
                left join prof pr on pr.customer_id = r.customer_id
                left join staff_user su on cast(su.id as text) = r.created_by
                where %s
                order by r.created_at desc, r.id desc
                """.formatted(p.on(ist("r.created_at")));
        List<List<Object>> rows = query(sql, p.params());
        long auto = rows.stream().filter(r -> "Automatic".equals(r.get(7))).count();
        return new Table(cols, rows, List.of(new Fact("Rejections", (long) rows.size(), ColumnType.NUMBER),
                new Fact("Automatic", auto, ColumnType.NUMBER)), List.of());
    }

    // ---- collections --------------------------------------------------------------------------

    private Table collectionCases(Period p) {
        List<Column> cols = List.of(Column.text("caseId", "Case ID"), Column.id("loanId", "Loan ID"),
                Column.id("customerId", "Customer ID"), Column.text("name", "Customer"), Column.text("mobile", "Mobile"),
                Column.text("bucket", "Bucket at opening"), Column.text("officer", "Officer"),
                Column.dateTime("openedAt", "Opened"), Column.date("dueDate", "Due date"),
                Column.text("loanStatus", "Loan status"), Column.money("principal", "Principal (Rs.)"),
                Column.money("outstanding", "Outstanding today (Rs.)"), Column.number("interactions", "Interactions logged"));
        String sql = "with " + PROF_CTE + """
                , il as (select collection_case_id, count(*) n from interaction_log group by collection_case_id)
                select cast(c.id as text), c.loan_id, l.customer_id, pr.full_name, pr.mobile, c.current_bucket, su.name,
                       c.created_at, l.due_date, cast(l.status as text), l.principal, 0, coalesce(il.n, 0)
                from collection_case c
                join loan l on l.id = c.loan_id
                left join prof pr on pr.customer_id = l.customer_id
                left join staff_user su on su.id = c.assigned_officer_id
                left join il on il.collection_case_id = c.id
                where %s
                order by c.created_at desc
                """.formatted(p.on(ist("c.created_at")));
        List<List<Object>> rows = query(sql, p.params());
        Map<Long, OutstandingBreakdown> priced = price(rows.stream().map(r -> ((Number) r.get(1)).longValue()).toList());
        for (List<Object> r : rows) {
            OutstandingBreakdown b = priced.get(((Number) r.get(1)).longValue());
            r.set(11, b == null ? 0L : b.outstandingPaise());
        }
        return totalled(cols, rows, List.of(new Fact("Cases", (long) rows.size(), ColumnType.NUMBER),
                new Fact("Outstanding today", sum(rows, 11), ColumnType.MONEY)));
    }

    private Table settlements(Period p) {
        List<Column> cols = List.of(Column.text("id", "Settlement ID"), Column.id("loanId", "Loan ID"),
                Column.text("name", "Customer"), Column.text("mobile", "Mobile"), Column.money("principal", "Principal (Rs.)"),
                Column.money("amount", "Settlement amount (Rs.)"), Column.text("status", "Status"),
                Column.text("proposedBy", "Proposed by"), Column.dateTime("proposedAt", "Proposed at"),
                Column.text("decidedBy", "Approved / rejected by"), Column.dateTime("decidedAt", "Decided at"));
        String sql = "with " + PROF_CTE + """
                select cast(s.id as text), c.loan_id, pr.full_name, pr.mobile, l.principal, s.settlement_amount, s.status,
                       pb.name, s.created_at, coalesce(ab.name, rb.name), coalesce(s.approved_at, s.rejected_at)
                from settlement s
                join collection_case c on c.id = s.collection_case_id
                join loan l on l.id = c.loan_id
                left join prof pr on pr.customer_id = l.customer_id
                left join staff_user pb on pb.id = s.proposed_by
                left join staff_user ab on ab.id = s.approved_by
                left join staff_user rb on rb.id = s.rejected_by
                where %s
                order by s.created_at desc
                """.formatted(p.on(ist("s.created_at")));
        return totalled(cols, query(sql, p.params()), List.of());
    }

    // ---- staff --------------------------------------------------------------------------------

    private Table staff() {
        List<Column> cols = List.of(Column.id("id", "Staff ID"), Column.text("name", "Name"), Column.text("email", "Email"),
                Column.text("mobile", "Mobile"), Column.text("role", "Role"), Column.text("status", "Status"),
                Column.text("department", "Department"), Column.text("designation", "Designation"),
                Column.dateTime("createdAt", "Created"), Column.dateTime("lastLogin", "Last sign-in"));
        String sql = """
                select s.id, s.name, s.email, s.mobile, s.role, s.status, s.department, s.designation, s.created_at,
                       s.active_session_at
                from staff_user s order by s.status, s.role, s.name
                """;
        List<List<Object>> rows = query(sql, new MapSqlParameterSource());
        long active = rows.stream().filter(r -> "ACTIVE".equals(r.get(5))).count();
        return new Table(cols, rows, List.of(new Fact("Staff accounts", (long) rows.size(), ColumnType.NUMBER),
                new Fact("Active", active, ColumnType.NUMBER)), List.of());
    }

    private Table staffActivity(Period p) {
        List<Column> cols = List.of(Column.id("staffId", "Staff ID"), Column.text("name", "Name"),
                Column.text("role", "Role"), Column.text("action", "Action"), Column.number("count", "Times"),
                Column.number("files", "Distinct files / loans"), Column.dateTime("last", "Last done"));
        String sql = """
                with acts as (
                    select su.id sid, e.action act, e.application_id ref, e.at
                    from application_event e join staff_user su on cast(su.id as text) = e.actor_id
                    where coalesce(e.actor_role, '') not in ('BORROWER', 'SYSTEM') and %s
                    union all
                    select su.id, 'REPAYMENT_' || cast(p.status as text), p.loan_id, p.decided_at
                    from payment p join staff_user su on su.id = p.decided_by
                    where p.decided_at is not null and p.status in ('VERIFIED', 'REJECTED') and %s
                )
                select su.id, su.name, su.role, a.act, count(*), count(distinct a.ref), max(a.at)
                from acts a join staff_user su on su.id = a.sid
                group by su.id, su.name, su.role, a.act
                order by su.name, count(*) desc
                """.formatted(p.on(ist("e.at")), p.on(ist("p.decided_at")));
        List<List<Object>> rows = query(sql, p.params());
        long people = rows.stream().map(r -> r.get(0)).distinct().count();
        return new Table(cols, rows, List.of(new Fact("Staff with activity", people, ColumnType.NUMBER),
                new Fact("Actions", sum(rows, 4), ColumnType.NUMBER)), List.of());
    }

    // ---- finance ------------------------------------------------------------------------------

    private Table expenses(Period p) {
        List<Column> cols = List.of(Column.id("id", "Expense ID"), Column.date("date", "Date"),
                Column.text("description", "Description"), Column.text("paidTo", "Paid to"),
                Column.money("amount", "Amount (Rs.)"), Column.text("notes", "Notes"),
                Column.bool("receipt", "Receipt attached"), Column.text("recordedBy", "Recorded by"));
        String sql = """
                select x.id, x.expense_date, x.description, x.paid_to, x.amount_paise, x.notes,
                       x.receipt_object_key is not null, coalesce(su.name, x.created_by)
                from company_expense x left join staff_user su on cast(su.id as text) = x.created_by
                where %s order by x.expense_date desc, x.id desc
                """.formatted(p.on("x.expense_date"));
        List<List<Object>> rows = query(sql, p.params());
        return totalled(cols, rows, List.of(new Fact("Total expenses", sum(rows, 4), ColumnType.MONEY)));
    }

    private Table feeIncome(Period p) {
        List<Column> cols = List.of(Column.id("loanId", "Loan ID"), Column.text("name", "Customer"),
                Column.date("disbursedOn", "Disbursed on"), Column.money("principal", "Principal (Rs.)"),
                Column.money("fee", "Processing fee (Rs.)"), Column.money("gst", "GST on fee (Rs.)"),
                Column.money("deducted", "Total deducted (Rs.)"), Column.money("net", "Net disbursed (Rs.)"));
        String sql = "with " + PROF_CTE + """
                select l.id, pr.full_name, l.disbursed_on, l.principal, coalesce(l.processing_fee, 0), coalesce(l.gst, 0),
                       coalesce(l.processing_fee, 0) + coalesce(l.gst, 0), coalesce(l.net_disbursed, 0)
                from loan l left join prof pr on pr.customer_id = l.customer_id
                where l.disbursed_on is not null and %s
                order by l.disbursed_on desc, l.id desc
                """.formatted(p.on("l.disbursed_on"));
        List<List<Object>> rows = query(sql, p.params());
        return totalled(cols, rows, List.of(new Fact("Fee earned", sum(rows, 4), ColumnType.MONEY),
                new Fact("GST collected", sum(rows, 5), ColumnType.MONEY)));
    }

    // ---- growth -------------------------------------------------------------------------------

    private Table leads(Period p) {
        List<Column> cols = List.of(Column.id("id", "Lead ID"), Column.text("name", "Name"), Column.text("mobile", "Mobile"),
                Column.text("email", "Email"), Column.text("city", "City"), Column.text("employer", "Employer"),
                Column.money("salary", "Monthly salary (Rs.)"), Column.money("interested", "Loan wanted (Rs.)"),
                Column.text("source", "Source"), Column.text("callStatus", "Call status"), Column.text("outcome", "Outcome"),
                Column.number("rating", "Quality (1-5)"), Column.text("createdBy", "Created by"),
                Column.text("dsa", "DSA"), Column.dateTime("createdAt", "Created"));
        String sql = """
                select x.id, x.name, x.mobile, x.email, x.city, x.employer, x.monthly_salary_paise,
                       x.loan_amount_interested_paise, x.source, x.call_status, x.lead_outcome, x.quality_rating,
                       cb.name, ds.name, x.created_at
                from lead x
                left join staff_user cb on cb.id = x.created_by_staff_id
                left join staff_user ds on ds.id = x.owner_dsa_id
                where %s order by x.created_at desc, x.id desc
                """.formatted(p.on(ist("x.created_at")));
        List<List<Object>> rows = query(sql, p.params());
        return new Table(cols, rows, List.of(new Fact("Leads", (long) rows.size(), ColumnType.NUMBER)), List.of());
    }

    private Table dsaCommissions(Period p) {
        List<Column> cols = List.of(Column.id("id", "Commission ID"), Column.text("dsa", "DSA"),
                Column.id("leadId", "Lead ID"), Column.id("customerId", "Customer ID"), Column.text("name", "Customer"),
                Column.id("loanId", "Loan ID"), Column.money("net", "Net disbursed (Rs.)"),
                Column.number("rate", "Rate (%)"), Column.money("amount", "Commission (Rs.)"), Column.text("status", "Status"),
                Column.dateTime("accruedAt", "Accrued"), Column.dateTime("payableAt", "Payable from"),
                Column.dateTime("paidAt", "Paid"), Column.text("txnRef", "Payment reference"));
        String sql = "with " + PROF_CTE + """
                select c.id, su.name, c.lead_id, c.customer_id, pr.full_name, c.loan_id, c.net_disbursed_paise,
                       round(c.rate_bps / 100.0, 2), c.amount_paise, c.status, c.accrued_at, c.payable_at, c.paid_at, c.txn_ref
                from dsa_commission c
                left join staff_user su on su.id = c.dsa_staff_id
                left join prof pr on pr.customer_id = c.customer_id
                where %s order by c.accrued_at desc, c.id desc
                """.formatted(p.on(ist("c.accrued_at")));
        return totalledWith(cols, query(sql, p.params()), List.of(), List.of(), Map.of(7, true));
    }

    private Table referralPayouts(Period p) {
        List<Column> cols = List.of(Column.id("id", "Payout ID"), Column.id("customerId", "Customer ID"),
                Column.text("name", "Customer"), Column.text("mobile", "Mobile"), Column.text("role", "Referrer / referee"),
                Column.money("amount", "Amount (Rs.)"), Column.text("status", "Status"), Column.id("loanId", "Qualifying loan"),
                Column.dateTime("createdAt", "Created"), Column.dateTime("paidAt", "Paid"), Column.text("paidBy", "Paid by"),
                Column.text("txnRef", "Payment reference"));
        String sql = "with " + PROF_CTE + """
                select r.id, r.beneficiary_customer_id, pr.full_name, pr.mobile, r.beneficiary_role, r.amount_paise, r.status,
                       r.qualifying_loan_id, r.created_at, r.paid_at, r.paid_by, r.txn_ref
                from referral_payout r left join prof pr on pr.customer_id = r.beneficiary_customer_id
                where %s order by r.created_at desc, r.id desc
                """.formatted(p.on(ist("r.created_at")));
        return totalled(cols, query(sql, p.params()), List.of());
    }

    private Table blocklist(Period p) {
        List<Column> cols = List.of(Column.id("id", "Entry ID"), Column.text("type", "Type"), Column.text("value", "Value"),
                Column.text("reason", "Reason"), Column.bool("active", "Active"), Column.text("addedBy", "Added by"),
                Column.dateTime("createdAt", "Added"));
        String sql = """
                select b.id, b.type, b.value, b.reason, b.active, coalesce(su.name, b.created_by), b.created_at
                from blocklist_entry b left join staff_user su on cast(su.id as text) = b.created_by
                where %s order by b.created_at desc
                """.formatted(p.on(ist("b.created_at")));
        return new Table(cols, query(sql, p.params()), List.of(), List.of());
    }

    // ---- plumbing -----------------------------------------------------------------------------

    /** A report's body before the envelope is added. */
    private record Table(List<Column> columns, List<List<Object>> rows, List<Fact> summary, List<String> notes,
                         boolean totals) {
        Table(List<Column> columns, List<List<Object>> rows, List<Fact> summary, List<String> notes) {
            this(columns, rows, summary, notes, true);
        }
    }

    /** An inclusive IST period, or {@link #ALL}. */
    record Period(LocalDate from, LocalDate to) {
        static final Period ALL = new Period(null, null);

        static Period of(LocalDate from, LocalDate to) {
            if (from == null && to == null) return ALL;
            LocalDate t = to == null ? LocalDate.now(IST) : to;
            LocalDate f = from == null ? LocalDate.of(2000, 1, 1) : from;
            if (f.isAfter(t)) {
                throw new BusinessException("INVALID_RANGE", "The period's start must not be after its end");
            }
            if (from != null && ChronoUnit.DAYS.between(f, t) > MAX_SPAN_DAYS) {
                throw new BusinessException("INVALID_RANGE", "A period may span at most 10 years");
            }
            return new Period(f, t);
        }

        boolean all() {
            return from == null;
        }

        /** A SQL predicate restricting a date expression to the period ({@code true} for all time). */
        String on(String dateExpr) {
            return all() ? "true" : "(" + dateExpr + " between :from and :to)";
        }

        MapSqlParameterSource params() {
            MapSqlParameterSource s = new MapSqlParameterSource();
            if (!all()) {
                s.addValue("from", from).addValue("to", to);
            }
            return s;
        }
    }

    /** A timestamptz column read as its IST calendar date. */
    private static String ist(String column) {
        return "cast(" + column + " at time zone 'Asia/Kolkata' as date)";
    }

    private List<List<Object>> query(String sql, MapSqlParameterSource params) {
        return jdbc.query(sql + (sql.contains(" limit ") ? "" : " limit " + (MAX_ROWS + 1)), params,
                (rs, i) -> rowOf(rs));
    }

    private static List<Object> rowOf(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        List<Object> row = new ArrayList<>(md.getColumnCount());
        for (int c = 1; c <= md.getColumnCount(); c++) {
            row.add(normalize(rs.getObject(c)));
        }
        return row;
    }

    /** JDBC values → JSON-friendly raw cells (dates as ISO strings, integers as longs). */
    static Object normalize(Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
        if (v instanceof Timestamp t) return t.toInstant().toString();
        if (v instanceof OffsetDateTime o) return o.toInstant().toString();
        if (v instanceof LocalDate d) return d.toString();
        if (v instanceof Instant i) return i.toString();
        if (v instanceof Integer || v instanceof Short) return ((Number) v).longValue();
        if (v instanceof BigDecimal b) {
            return b.scale() <= 0 ? (Object) b.longValueExact() : b.doubleValue();
        }
        if (v instanceof java.util.UUID u) return u.toString();
        return v;
    }

    private long[] pair(String sql, MapSqlParameterSource params) {
        return jdbc.queryForObject(sql, params, (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)});
    }

    private long scalar(String sql) {
        Long v = jdbc.queryForObject(sql, new MapSqlParameterSource(), Long.class);
        return v == null ? 0 : v;
    }

    private Map<Long, OutstandingBreakdown> price(List<Long> loanIds) {
        Map<Long, OutstandingBreakdown> out = new HashMap<>();
        List<Long> ids = new ArrayList<>(new java.util.LinkedHashSet<>(loanIds));
        LocalDate today = LocalDate.now(IST);
        for (int i = 0; i < ids.size(); i += PRICING_CHUNK) {
            List<Loan> loans = loanRepository.findAllById(ids.subList(i, Math.min(ids.size(), i + PRICING_CHUNK)));
            out.putAll(repayments.outstandingBreakdownsForAll(loans, today));
        }
        return out;
    }

    private static List<Object> row(Object... cells) {
        List<Object> r = new ArrayList<>(cells.length);
        for (Object c : cells) r.add(normalize(c));
        return r;
    }

    private static String iso(LocalDate d) {
        return d == null ? null : d.toString();
    }

    private static LocalDate localDate(ResultSet rs, int col) throws SQLException {
        java.sql.Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    private static String bucket(long dpd) {
        if (dpd <= 0) return "Current";
        if (dpd <= 30) return "1-30";
        if (dpd <= 60) return "31-60";
        if (dpd <= 90) return "61-90";
        return "90+";
    }

    private static long sum(List<List<Object>> rows, int col) {
        long s = 0;
        for (List<Object> r : rows) {
            if (r.get(col) instanceof Number n) s += n.longValue();
        }
        return s;
    }

    /** A table whose MONEY columns' totals are added as facts when the caller gave none. */
    private static Table totalled(List<Column> cols, List<List<Object>> rows, List<Fact> facts) {
        return totalledWith(cols, rows, facts, List.of(), Map.of());
    }

    /**
     * Fill in the headline figures: the caller's own facts, else the row count plus every MONEY
     * column's total. {@code skip} marks columns not to total (ratios, percentages).
     */
    private static Table totalledWith(List<Column> cols, List<List<Object>> rows, List<Fact> facts, List<String> notes,
                                      Map<Integer, Boolean> skip) {
        if (!facts.isEmpty()) return new Table(cols, rows, facts, notes);
        List<Fact> out = new ArrayList<>();
        out.add(new Fact("Rows", (long) rows.size(), ColumnType.NUMBER));
        Map<String, Long> totals = new LinkedHashMap<>();
        for (int i = 0; i < cols.size() && out.size() + totals.size() < 5; i++) {
            if (cols.get(i).type() == ColumnType.MONEY && !skip.containsKey(i)) {
                totals.put(cols.get(i).label().replace(" (Rs.)", ""), sum(rows, i));
            }
        }
        totals.forEach((label, v) -> out.add(new Fact(label + " (total)", v, ColumnType.MONEY)));
        return new Table(cols, rows, out, notes);
    }

    private static void requireAdmin() {
        CurrentActor actor = ActorContext.get();
        if (actor == null || !"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "Reports are available to ADMIN only");
        }
    }
}
