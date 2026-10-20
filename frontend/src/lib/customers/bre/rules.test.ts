import { describe, expect, it } from "vitest";
import type { DocumentView, ProfileView, StepResult } from "@/lib/api/applications";
import type { JsonValue } from "@/lib/credit/provider-report";
import { parseBureauReport, type BureauAccount, type BureauReport } from "@/lib/customers/bre/bureau-report";
import { evaluateBre, mergeSteps, rollup, type BreInput, type BreRule } from "@/lib/customers/bre/rules";
import combine from "@/lib/customers/bre/__fixtures__/crif-combine-sample.json";
import kba from "@/lib/customers/bre/__fixtures__/crif-auth-answer-sample.json";

const TODAY = new Date(2026, 9, 10);

const acct = (o: Partial<BureauAccount>): BureauAccount => ({
  lender: "LENDER", accountNumber: null, accountType: "Personal Loan", isPersonalLoan: true, ownership: "INDIVIDUAL",
  ownershipLabel: "Individual", lenderType: "PRB", isNbfc: false, status: "ACTIVE", statusLabel: "Active",
  openedOn: "2026-02-01", closedOn: null, lastReportedOn: "2026-09-30", overdueRupees: 0, balanceRupees: 1000,
  history: [{ month: "2026-09", dpd: 0, npa: false }, { month: "2026-08", dpd: 0, npa: false }], historyExact: true,
  occupation: null, ...o,
});

const report = (accounts: BureauAccount[], extra: Partial<BureauReport> = {}): BureauReport => ({
  kind: "CRIF", source: "RAW", applicationId: 318, reportDate: "2026-10-01", score: 712, accounts,
  occupations: [{ value: "SALARIED", reportedOn: "2026-02-15", where: "Employment details" }], incomes: [], addresses: [], ...extra,
});

const step = (checkType: string, derived: Record<string, unknown>, status: StepResult["status"] = "PASS"): StepResult =>
  ({ checkType, status, message: null, derived } as StepResult);

const doc = (docType: string, id: number): { applicationId: number; doc: DocumentView } =>
  ({ applicationId: 318, doc: { id, docType, fileName: `${docType}.jpg`, contentType: "image/jpeg", sizeBytes: 1, uploadedAt: "2026-09-01T00:00:00Z" } as DocumentView });

const profile = {
  fullName: "ASHA VERMA", pan: "ABCDE1234F", employmentStatus: "SALARIED", employer: "Sprinklr",
  monthlySalaryPaise: 5_000_000, aadhaar: "234567891234", uan: "100000000001", officialEmail: "asha@sprinklr.com",
} as unknown as ProfileView;

const clean = (): BreInput => ({
  today: TODAY,
  profile,
  steps: [
    step("PAN", { fullName: "ASHA VERMA", panNumber: "ABCDE1234F", aadhaarLinked: true }),
    step("PAN_AADHAAR_LINK", { linked: true, code: "EF40124" }),
    step("AADHAAR", { fullName: "Asha Verma", maskedAadhaar: "XXXXXXXX1234" }),
    step("EMPLOYMENT", { found: true, employed: true, uan: "100000000001", employerName: "SPRINKLR INDIA PVT LTD" }),
    step("EMAIL", { establishmentMatched: true, companyName: "Sprinklr", domain: "sprinklr.com" }),
  ],
  report: report([acct({}), acct({ lender: "NBFC CO", lenderType: "NBF", isNbfc: true, accountType: "Consumer Loan", isPersonalLoan: false })]),
  bureau: { state: "FOUND", score: 712, source: "FINTRIX_CRIF", generatedAt: "2026-10-01T09:00:00Z" },
  aadhaar: "234567891234",
  documents: [doc("AADHAAR_CARD_FRONT", 1), doc("PAN_CARD_FRONT", 2), doc("SALARY_SLIP", 3), doc("SALARY_SLIP", 4)],
  bankSalaryPaise: null,
  bankAnalyserLive: false,
});

const byId = (rules: BreRule[], id: number) => rules.find((r) => r.id === id)!;

describe("evaluateBre — a clean file", () => {
  const { rules, counts } = evaluateBre(clean());

  it("validates the bureau and occupation rules", () => {
    expect([1, 2, 3, 4, 5, 6].map((id) => byId(rules, id).outcome)).toEqual(["PASS", "PASS", "PASS", "PASS", "PASS", "PASS"]);
  });

  it("asks for a manual look where only pictures or slips can settle it", () => {
    expect([7, 8, 9, 10].map((id) => byId(rules, id).outcome)).toEqual(["MANUAL", "MANUAL", "MANUAL", "MANUAL"]);
    expect(counts).toEqual({ PASS: 6, FLAG: 0, MANUAL: 4, UNKNOWN: 0 });
  });

  it("shows each comparison side by side with the documents a reviewer can open", () => {
    const r8 = byId(rules, 9);
    expect(r8.compare?.map((c) => c.outcome)).toEqual(["PASS", "PASS", "MANUAL"]);
    expect(r8.compare?.[2].docTypes).toContain("AADHAAR_CARD_FRONT");
  });
});

describe("evaluateBre — flags", () => {
  it("rule 1: score at or below 600, and a bureau identity mismatch", () => {
    const i = clean();
    i.bureau = { ...i.bureau, score: 600 };
    expect(byId(evaluateBre(i).rules, 1).outcome).toBe("FLAG");
    const j = clean();
    j.steps = [...j.steps, step("BUREAU", { identityMismatch: "Bureau report PAN does not match" })];
    expect(byId(evaluateBre(j).rules, 1).summary).toContain("PAN does not match");
  });

  it("rule 2: PAN not linked to Aadhaar flags; no check yet is unknown", () => {
    const i = clean();
    i.steps = i.steps.map((x) => (x.checkType === "PAN_AADHAAR_LINK" ? step("PAN_AADHAAR_LINK", { linked: false, code: "EF40125" }, "REVIEW") : x));
    expect(byId(evaluateBre(i).rules, 2).outcome).toBe("FLAG");
    const j = clean();
    j.steps = j.steps.filter((x) => x.checkType !== "PAN_AADHAAR_LINK");
    expect(byId(evaluateBre(j).rules, 2).outcome).toBe("UNKNOWN");
  });

  it("rule 3: only an old personal loan", () => {
    const i = clean();
    i.report = report([acct({ status: "CLOSED", statusLabel: "Closed", openedOn: "2023-01-01", closedOn: "2024-03-28" })]);
    expect(byId(evaluateBre(i).rules, 3).outcome).toBe("FLAG");
  });

  it("rule 4: a guarantor loan in the window", () => {
    const i = clean();
    i.report = report([acct({ ownership: "GUARANTOR", ownershipLabel: "Guarantor" })]);
    const r3 = byId(evaluateBre(i).rules, 4);
    expect(r3.outcome).toBe("FLAG");
    expect(r3.accounts?.[0].flagged).toBe(true);
  });

  it("rule 5: no NBFC loan, and an NBFC loan with DPD over 10 days", () => {
    const i = clean();
    i.report = report([acct({})]);
    expect(byId(evaluateBre(i).rules, 5).outcome).toBe("FLAG");
    const j = clean();
    j.report = report([acct({ isNbfc: true, lenderType: "NBF", history: [{ month: "2026-08", dpd: 11, npa: false }] })]);
    const r4 = byId(evaluateBre(j).rules, 5);
    expect(r4.outcome).toBe("FLAG");
    expect(r4.summary).toContain("11 DPD");
  });

  it("rule 5: DPD of exactly 10 days or before the window does not flag", () => {
    const i = clean();
    i.report = report([acct({ isNbfc: true, lenderType: "NBF", history: [{ month: "2026-08", dpd: 10, npa: false }, { month: "2025-11", dpd: 90, npa: false }] })]);
    expect(byId(evaluateBre(i).rules, 5).outcome).toBe("PASS");
  });

  it("rule 6: self-employed, army, advocate and Ministry of Home Affairs", () => {
    const self = clean();
    self.profile = { ...profile, employmentStatus: "SELF_EMPLOYED" };
    expect(byId(evaluateBre(self).rules, 6).outcome).toBe("FLAG");

    const army = clean();
    army.profile = { ...profile, employer: "Indian Army" };
    expect(byId(evaluateBre(army).rules, 6).summary).toContain("Army");

    const lawyer = clean();
    lawyer.steps = [...lawyer.steps.filter((s) => s.checkType !== "EMPLOYMENT"), step("EMPLOYMENT", { found: true, employed: true, employerName: "KUMAR & ASSOCIATES ADVOCATES" })];
    expect(byId(evaluateBre(lawyer).rules, 6).summary).toContain("Lawyer");

    const mha = clean();
    mha.profile = { ...profile, officialEmail: "asha@mha.gov.in" };
    mha.steps = mha.steps.filter((s) => s.checkType !== "EMAIL");
    expect(byId(evaluateBre(mha).rules, 6).summary).toContain("Ministry of Home Affairs");

    const bureau = clean();
    bureau.report = report(bureau.report!.accounts, { occupations: [{ value: "Self Employed Professional", reportedOn: null, where: "x" }] });
    expect(byId(evaluateBre(bureau).rules, 6).outcome).toBe("FLAG");
  });

  it("rule 6: an employer address on a business park is not self-employment", () => {
    const i = clean();
    i.report = report(i.report!.accounts, { addresses: [{ value: "SAMPLE EMPLOYER PVT LTD BUSINESS PARK", reportedOn: null, where: "Address" }] });
    expect(byId(evaluateBre(i).rules, 6).outcome).toBe("PASS");
  });

  it("rule 7: Aadhaar not linked, or a different name on Aadhaar", () => {
    const i = clean();
    i.steps = [step("PAN", { fullName: "ASHA VERMA", aadhaarLinked: false }), ...i.steps.slice(1)];
    expect(byId(evaluateBre(i).rules, 7).outcome).toBe("FLAG");
    const j = clean();
    j.steps = [j.steps[0], step("AADHAAR", { fullName: "RAVI KUMAR", maskedAadhaar: "XXXXXXXX1234" }), ...j.steps.slice(2)];
    expect(byId(evaluateBre(j).rules, 7).outcome).toBe("FLAG");
  });

  it("rule 8: no EPFO record, a different employer, or a different UAN", () => {
    const none = clean();
    none.steps = [...none.steps.filter((s) => s.checkType !== "EMPLOYMENT"), step("EMPLOYMENT", { found: false })];
    expect(byId(evaluateBre(none).rules, 8).outcome).toBe("FLAG");
    const other = clean();
    other.steps = [...other.steps.filter((s) => s.checkType !== "EMPLOYMENT"), step("EMPLOYMENT", { found: true, employed: true, uan: "100000000001", employerName: "ECLERX SERVICES LIMITED" })];
    expect(byId(evaluateBre(other).rules, 8).outcome).toBe("FLAG");
    const uan = clean();
    uan.profile = { ...profile, uan: "100000000009" };
    expect(byId(evaluateBre(uan).rules, 8).outcome).toBe("FLAG");
  });

  it("rule 9: typed Aadhaar disagrees with DigiLocker", () => {
    const i = clean();
    i.aadhaar = "234567899999";
    expect(byId(evaluateBre(i).rules, 9).outcome).toBe("FLAG");
  });

  it("rule 10: bank-statement salary off by more than 10%", () => {
    const i = clean();
    i.bankAnalyserLive = true;
    i.bankSalaryPaise = 3_000_000;
    expect(byId(evaluateBre(i).rules, 10).outcome).toBe("FLAG");
    i.bankSalaryPaise = 4_800_000;
    expect(byId(evaluateBre(i).rules, 10).outcome).toBe("MANUAL");
  });
});

describe("evaluateBre — missing data is never a pass or a fail", () => {
  it("bureau not pulled: rules 1-4 have no data", () => {
    const i = clean();
    i.report = null;
    i.bureau = { state: "NOT_FETCHED", score: null, source: null, generatedAt: null };
    expect([1, 3, 4, 5].map((id) => byId(evaluateBre(i).rules, id).outcome)).toEqual(["UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN"]);
  });

  it("a confirmed no-hit flags the score", () => {
    const i = clean();
    i.report = null;
    i.bureau = { state: "NO_RECORD", score: null, source: null, generatedAt: null };
    expect(byId(evaluateBre(i).rules, 1).outcome).toBe("FLAG");
  });

  it("before DigiLocker (pre-sanction) rules 6 and 8 wait for it", () => {
    const i = clean();
    i.steps = i.steps.filter((s) => s.checkType !== "AADHAAR");
    const { rules } = evaluateBre(i);
    expect(byId(rules, 7).outcome).toBe("UNKNOWN");
    expect(byId(rules, 9).outcome).toBe("UNKNOWN");
  });

  it("parsed tradelines carry no ownership, so rule 3 says so", () => {
    const i = clean();
    i.report = report([acct({ ownership: null, ownershipLabel: null })], { source: "PARSED" });
    expect(byId(evaluateBre(i).rules, 4).outcome).toBe("UNKNOWN");
  });
});

describe("evaluateBre — against the real CRIF samples", () => {
  const base = clean();

  it("crif_combine: score 799 passes; the only personal loan closed in 2024 and no NBFC loan in the window", () => {
    const i = { ...base, report: parseBureauReport(combine as unknown as JsonValue, 318), bureau: { ...base.bureau, score: 799, generatedAt: "2026-08-22T00:00:00Z" } };
    const { rules } = evaluateBre(i);
    expect(byId(rules, 1).outcome).toBe("PASS");
    expect(byId(rules, 3).outcome).toBe("FLAG");
    expect(byId(rules, 5).outcome).toBe("FLAG");
  });

  it("KBA sample: score 510 and an NBFC consumer loan 900 days past due", () => {
    const i = { ...base, report: parseBureauReport(kba as unknown as JsonValue, 9), bureau: { ...base.bureau, score: 510 } };
    const { rules } = evaluateBre(i);
    expect(byId(rules, 1).outcome).toBe("FLAG");
    expect(byId(rules, 5).outcome).toBe("FLAG");
    expect(byId(rules, 5).summary).toContain("900 DPD");
  });
});

describe("helpers", () => {
  it("rollup: FLAG > UNKNOWN > MANUAL > PASS, ignoring informational checks", () => {
    expect(rollup([{ label: "a", outcome: "PASS" }, { label: "b", outcome: "FLAG", informational: true }])).toBe("PASS");
    expect(rollup([{ label: "a", outcome: "MANUAL" }, { label: "b", outcome: "UNKNOWN" }])).toBe("UNKNOWN");
    expect(rollup([{ label: "a", outcome: "MANUAL" }, { label: "b", outcome: "PASS" }])).toBe("MANUAL");
    expect(rollup([{ label: "a", outcome: "UNKNOWN" }, { label: "b", outcome: "FLAG" }])).toBe("FLAG");
  });

  it("mergeSteps keeps the selected file's row and tops up from earlier files", () => {
    const merged = mergeSteps([[step("PAN", { a: 1 })], [step("PAN", { a: 2 }), step("BUREAU", {})]]);
    expect(merged.map((s) => [s.checkType, s.derived.a])).toEqual([["PAN", 1], ["BUREAU", undefined]]);
  });

  it("staging pass: rules 1, 4, 6, 7, 9 report PASS; 3, 5, 8, 10 stay real", () => {
    const i = clean();
    i.bureau = { ...i.bureau, score: 500 };
    i.aadhaar = "234567899999";
    i.report = report([acct({ status: "CLOSED", statusLabel: "Closed", openedOn: "2023-01-01", closedOn: "2024-03-28" })]);
    const real = evaluateBre(i).rules;
    expect([1, 3, 9].map((id) => byId(real, id).outcome)).toEqual(["FLAG", "FLAG", "FLAG"]);
    const staged = evaluateBre({ ...i, stagingPass: true }).rules;
    expect([1, 4, 6, 7, 9].map((id) => byId(staged, id).outcome)).toEqual(["PASS", "PASS", "PASS", "PASS", "PASS"]);
    expect([3, 5, 8, 10].map((id) => byId(staged, id).outcome)).toEqual([3, 5, 8, 10].map((id) => byId(real, id).outcome));
  });
});
