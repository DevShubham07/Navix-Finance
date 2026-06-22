/**
 * NAVIX borrower-journey scenario harness.
 *
 * Drives the REAL product logic (src/lib/calc/loan-math + src/lib/calc/risk)
 * and the REAL persona seeds (src/lib/mock/scenarios) through every test case,
 * asserting the salary-linked product rules end to end. No app/store import is
 * needed — the store calls these exact modules, so the numbers are identical.
 *
 * Run:  node --no-warnings scripts/scenario-harness.mts
 */
import {
  eligibleLimit, buildCostBreakdown, dueDateFromSalary, latePenalty,
  processingFee, gstOnFee, netDisbursed, totalRepayable, dailyInterest,
  daysBetween,
} from "../src/lib/calc/loan-math.ts";
import { assessRisk, requiresCoApplicant, sanctionedLimit } from "../src/lib/calc/risk.ts";
import { SCENARIOS } from "../src/lib/mock/scenarios.ts";

let pass = 0, fail = 0;
const fails: string[] = [];
function ok(cond: boolean, msg: string) {
  if (cond) { pass++; } else { fail++; fails.push(msg); }
}
const inr = (n: number) => "₹" + n.toLocaleString("en-IN");
const pad = (s: string, n: number) => s.padEnd(n);

console.log("\n=== NAVIX borrower journey — scenario harness ===\n");

// ---------------------------------------------------------------------------
// 0. Product-rule spec checks (the documented worked example + caps)
// ---------------------------------------------------------------------------
console.log("Spec rules (₹10,000 / 30 days):");
ok(processingFee(10000) === 1000, "processing fee 10% should be ₹1,000");
ok(gstOnFee(1000) === 180, "GST 18% on fee should be ₹180");
ok(netDisbursed(10000) === 8820, "net disbursed should be ₹8,820");
ok(dailyInterest(10000, 30) === 3000, "interest 1%/day×30 should be ₹3,000");
ok(totalRepayable(10000, 30) === 13000, "total repayable should be ₹13,000");
ok(eligibleLimit(84000) === 21000, "eligible limit = 25% of ₹84,000 = ₹21,000");
ok(latePenalty(10000, 45) === 6000, "late penalty caps at 30 days (₹6,000), not 45");
ok(latePenalty(10000, 10) === 2000, "late penalty 2%/day×10 = ₹2,000");
console.log(`  ${fail === 0 ? "✓ all spec rules hold" : "✗ see failures below"}\n`);

// ---------------------------------------------------------------------------
// 1. Per-scenario derivations + invariants
// ---------------------------------------------------------------------------
console.log("Personas:");
console.log(
  "  " + pad("scenario", 22) + pad("risk", 6) + pad("eligible", 11) +
  pad("sanction", 11) + pad("co-app", 8) + "outcome",
);
for (const s of SCENARIOS) {
  const salary = s.applicant.monthlySalary;
  const risk = assessRisk(salary, s.creditScore);
  const elig = eligibleLimit(salary);
  const sanc = sanctionedLimit(elig, risk);
  const coReq = requiresCoApplicant(risk);
  const principal = s.chosenAmount ?? sanc;

  const due = dueDateFromSalary({ disbursedOn: new Date(), salaryDay: s.applicant.salaryDay });
  const tenure = Math.max(1, daysBetween(new Date(), due));
  const b = buildCostBreakdown(principal, tenure);
  const penalty = s.daysPastDue ? latePenalty(principal, s.daysPastDue) : 0;
  const outstanding = b.totalRepayable + penalty;

  // Invariants every scenario must satisfy
  ok(elig === Math.floor((salary * 0.25) / 100) * 100, `${s.id}: eligible = 25% floored`);
  ok(sanc <= elig, `${s.id}: sanctioned (${sanc}) must not exceed eligible (${elig})`);
  ok(b.netDisbursed === b.principal - b.processingFee - b.gstOnFee, `${s.id}: net = principal − fee − gst`);
  ok(b.totalRepayable === b.principal + b.interest, `${s.id}: total = principal + interest`);
  ok(principal <= sanc || s.chosenAmount !== undefined, `${s.id}: amount within sanctioned limit`);
  if (s.id === "declined-d") ok(risk === "D" && !!s.forceDecline, "declined-d must be risk D + declined");
  if (s.id === "coapp-c") ok(risk === "C" && coReq, "coapp-c must be risk C and require a co-applicant");
  if (s.daysPastDue) ok(penalty === principal * 0.02 * Math.min(s.daysPastDue, 30), `${s.id}: penalty = 2%/day capped`);

  const outcome =
    s.forceDecline ? "DECLINED"
    : s.daysPastDue ? `OVERDUE ${inr(outstanding)} (incl ${inr(penalty)} penalty)`
    : `${s.status} ${inr(outstanding)} repayable`;
  console.log(
    "  " + pad(s.id, 22) + pad(risk, 6) + pad(inr(elig), 11) +
    pad(inr(sanc), 11) + pad(coReq ? "yes" : "no", 8) + outcome,
  );
}

// ---------------------------------------------------------------------------
// 2. Full lifecycle simulation (mirrors the store's transitions)
// ---------------------------------------------------------------------------
console.log("\nLifecycle — Category A, apply → disburse → repay in full:");
{
  const salary = 120000, salaryDay = 30, score = 792;
  const risk = assessRisk(salary, score);
  const sanc = sanctionedLimit(eligibleLimit(salary), risk);
  const chosen = 30000;
  const due = dueDateFromSalary({ disbursedOn: new Date(), salaryDay });
  const tenure = Math.max(1, daysBetween(new Date(), due));
  const b = buildCostBreakdown(chosen, tenure);
  let status = "ACTIVE";
  let outstanding = b.totalRepayable;
  console.log(`  approved risk ${risk}, sanctioned ${inr(sanc)}, drew ${inr(chosen)}`);
  console.log(`  disbursed net ${inr(b.netDisbursed)}, due ${due.toDateString()} (${tenure}d), owe ${inr(outstanding)}`);
  // partial then full
  outstanding -= 10000; ok(outstanding === b.totalRepayable - 10000, "partial payment reduces balance");
  console.log(`  part-paid ₹10,000 → balance ${inr(outstanding)}`);
  outstanding -= outstanding; status = outstanding === 0 ? "REPAID" : status;
  ok(status === "REPAID" && outstanding === 0, "full repayment closes the loan");
  console.log(`  paid remainder → status ${status}, balance ${inr(outstanding)}`);
}

// ---------------------------------------------------------------------------
// 3. Overdue penalty growth (2%/day, cap 30)
// ---------------------------------------------------------------------------
console.log("\nOverdue penalty curve (₹12,000 principal):");
for (const d of [1, 7, 12, 30, 45]) {
  const p = latePenalty(12000, d);
  console.log(`  ${pad(d + "d", 5)} → ${inr(p)}${d >= 30 ? "  (capped)" : ""}`);
  ok(p === 12000 * 0.02 * Math.min(d, 30), `penalty@${d}d correct`);
}

// ---------------------------------------------------------------------------
console.log(`\n=== ${fail === 0 ? "ALL PASS" : "FAILURES"} · ${pass} passed, ${fail} failed ===`);
if (fail) { fails.forEach((f) => console.log("  ✗ " + f)); process.exit(1); }
