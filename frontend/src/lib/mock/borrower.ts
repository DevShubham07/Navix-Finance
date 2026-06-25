"use client";

import { create } from "zustand";
import { persist } from "zustand/middleware";
import { buildCostBreakdown, dueDateFromSalary, eligibleLimit as calcEligibleLimit } from "@/lib/calc/loan-math";
import {
  assessRisk,
  RISK_LIMIT_MULTIPLIER,
  requiresCoApplicant,
  sanctionedLimit as sanctionedFromBase,
} from "@/lib/calc/risk";
import type { LoanCostBreakdown } from "@/lib/domain/loan";
import type { RiskCategory } from "./types";

export type { RiskCategory };
export { assessRisk, RISK_LIMIT_MULTIPLIER };

/** Customer-facing status journey (see product flow §10). */
export type BorrowerStatus =
  | "NEW"
  | "APPLIED"
  | "UNDER_REVIEW"
  | "APPROVED"
  | "DOCS_SIGNED"
  | "BANK_VERIFIED"
  | "DISBURSING"
  | "ACTIVE"
  | "REPAID"
  | "DECLINED"
  | "OVERDUE";

/** Per-check KYC state surfaced to the borrower. */
export type KycCheck = "PENDING" | "IN_PROGRESS" | "VERIFIED" | "FAILED";

export interface KycState {
  pan: KycCheck;
  aadhaar: KycCheck;
  selfie: KycCheck;
  address: KycCheck;
  bank: KycCheck;
}

export interface CoApplicant {
  fullName: string;
  pan: string;
  mobile: string;
  relationship: string;
}

/** Everything captured during onboarding for one applicant. */
export interface ApplicantProfile {
  fullName: string;
  pan: string;
  aadhaar: string;
  mobile: string;
  mobileVerified: boolean;
  email: string;
  employer: string;
  designation: string;
  uan: string;
  monthlySalary: number;
  /** Day-of-month the salary lands. */
  salaryDay: number;
  bankName: string;
  accountLast4: string;
  ifsc: string;
  financialsLinked: boolean;
  addressLine: string;
  city: string;
  pin: string;
  coApplicant?: CoApplicant;
}

export interface BorrowerRepayment {
  id: string;
  amount: number;
  method: "UPI" | "BANK_TRANSFER";
  txnRef: string;
  at: string;
}

export interface BorrowerLoan {
  id: string;
  principal: number;
  costBreakdown: LoanCostBreakdown;
  dueDateISO: string;
  disbursedAtISO: string;
  outstanding: number;
  /** Penalty accrued so far (overdue path). */
  penalty: number;
  repayments: BorrowerRepayment[];
}

interface BorrowerJourneyState {
  status: BorrowerStatus;
  applicant: ApplicantProfile;
  kyc: KycState;
  riskCategory: RiskCategory;
  creditScore: number;
  coApplicantRequired: boolean;
  declineReason?: string;
  chosenAmount: number;
  documentsSigned: boolean;
  bankVerified: boolean;
  loan?: BorrowerLoan;

  /** 25% of monthly salary (statutory cap, before risk haircut). */
  eligibleLimit: () => number;
  /** Sanctioned limit after the risk-category haircut. */
  sanctionedLimit: () => number;

  beginApplication: () => void;
  updateApplicant: (patch: Partial<ApplicantProfile>) => void;
  verifyMobile: () => void;
  setKyc: (patch: Partial<KycState>) => void;
  /** Submit the completed onboarding form; assigns risk + credit score. */
  submitForReview: () => void;
  startReview: () => void;
  approve: () => void;
  decline: (reason: string) => void;
  chooseAmount: (amount: number) => void;
  signDocuments: () => void;
  verifyBank: () => void;
  disburse: () => void;
  repay: (amount: number, method: "UPI" | "BANK_TRANSFER", txnRef: string) => void;
  /** Returning customer: reuse the profile, re-open a fresh approved offer. */
  reborrow: () => void;
  /** Seed an end-to-end scenario for demo / testing. */
  loadScenario: (s: ScenarioSeed) => void;
  reset: () => void;
}

/** A reproducible test persona + outcome used by the DemoBar and the harness. */
export interface ScenarioSeed {
  id: string;
  label: string;
  description: string;
  applicant: Partial<ApplicantProfile> & { monthlySalary: number; salaryDay: number };
  creditScore: number;
  /** Where to drop the journey so the tester can pick up mid-flow. */
  status: BorrowerStatus;
  /** Pre-chosen amount (defaults to sanctioned limit). */
  chosenAmount?: number;
  /** For the overdue scenario: how many days past the due date. */
  daysPastDue?: number;
  forceDecline?: string;
}

let rc = 0;
const nowIso = () => new Date().toISOString();

const EMPTY_APPLICANT: ApplicantProfile = {
  fullName: "",
  pan: "",
  aadhaar: "",
  mobile: "",
  mobileVerified: false,
  email: "",
  employer: "",
  designation: "",
  uan: "",
  monthlySalary: 0,
  salaryDay: 1,
  bankName: "",
  accountLast4: "",
  ifsc: "",
  financialsLinked: false,
  addressLine: "",
  city: "",
  pin: "",
};

const FRESH_KYC: KycState = {
  pan: "PENDING",
  aadhaar: "PENDING",
  selfie: "PENDING",
  address: "PENDING",
  bank: "PENDING",
};

function buildLoan(principal: number, salaryDay: number, daysPastDue = 0): BorrowerLoan {
  const disbursedOn = new Date(Date.now() - Math.max(0, daysPastDue + 1) * 864e5);
  const due =
    daysPastDue > 0
      ? new Date(Date.now() - daysPastDue * 864e5)
      : dueDateFromSalary({ disbursedOn, salaryDay });
  const tenureDays = Math.max(1, Math.round((due.getTime() - disbursedOn.getTime()) / 864e5));
  const breakdown = buildCostBreakdown(principal, tenureDays);
  const penalty = daysPastDue > 0 ? Math.round(principal * 0.02 * Math.min(daysPastDue, 30)) : 0;
  return {
    id: `LN-${(rc += 1).toString().padStart(4, "0")}`,
    principal,
    costBreakdown: breakdown,
    dueDateISO: due.toISOString(),
    disbursedAtISO: disbursedOn.toISOString(),
    outstanding: breakdown.totalRepayable + penalty,
    penalty,
    repayments: [],
  };
}

export const useBorrowerJourney = create<BorrowerJourneyState>()(
  persist(
    (set, get) => ({
      status: "NEW",
      applicant: { ...EMPTY_APPLICANT },
      kyc: { ...FRESH_KYC },
      riskCategory: "B",
      creditScore: 0,
      coApplicantRequired: false,
      chosenAmount: 0,
      documentsSigned: false,
      bankVerified: false,
      loan: undefined,

      eligibleLimit: () => calcEligibleLimit(get().applicant.monthlySalary),
      sanctionedLimit: () => sanctionedFromBase(calcEligibleLimit(get().applicant.monthlySalary), get().riskCategory),

      beginApplication: () =>
        set({
          status: "NEW",
          applicant: { ...EMPTY_APPLICANT },
          kyc: { ...FRESH_KYC },
          riskCategory: "B",
          creditScore: 0,
          coApplicantRequired: false,
          declineReason: undefined,
          chosenAmount: 0,
          documentsSigned: false,
          bankVerified: false,
          loan: undefined,
        }),

      updateApplicant: (patch) => set((s) => ({ applicant: { ...s.applicant, ...patch } })),

      verifyMobile: () => set((s) => ({ applicant: { ...s.applicant, mobileVerified: true } })),

      setKyc: (patch) => set((s) => ({ kyc: { ...s.kyc, ...patch } })),

      submitForReview: () =>
        set((s) => {
          const creditScore = s.creditScore || 730;
          const risk = assessRisk(s.applicant.monthlySalary, creditScore);
          return {
            status: "APPLIED",
            creditScore,
            riskCategory: risk,
            coApplicantRequired: requiresCoApplicant(risk),
          };
        }),

      startReview: () => set({ status: "UNDER_REVIEW" }),

      approve: () =>
        set((s) => ({
          status: "APPROVED",
          chosenAmount: s.chosenAmount || get().sanctionedLimit(),
        })),

      decline: (reason) => set({ status: "DECLINED", declineReason: reason }),

      chooseAmount: (amount) => set({ chosenAmount: amount }),

      signDocuments: () => set({ status: "DOCS_SIGNED", documentsSigned: true }),

      verifyBank: () =>
        set((s) => ({
          status: "BANK_VERIFIED",
          bankVerified: true,
          kyc: { ...s.kyc, bank: "VERIFIED" },
        })),

      disburse: () => {
        const { chosenAmount, applicant } = get();
        set({ status: "ACTIVE", loan: buildLoan(chosenAmount, applicant.salaryDay) });
      },

      repay: (amount, method, txnRef) =>
        set((s) => {
          if (!s.loan) return s;
          const outstanding = Math.max(0, s.loan.outstanding - amount);
          const repayment: BorrowerRepayment = { id: `RP-${(rc += 1)}`, amount, method, txnRef, at: nowIso() };
          return {
            status: outstanding === 0 ? "REPAID" : s.status,
            loan: { ...s.loan, outstanding, repayments: [repayment, ...s.loan.repayments] },
          };
        }),

      reborrow: () =>
        set((s) => {
          const risk = assessRisk(s.applicant.monthlySalary, s.creditScore || 730);
          return {
            status: "APPROVED",
            riskCategory: risk,
            coApplicantRequired: requiresCoApplicant(risk),
            chosenAmount: 0,
            documentsSigned: false,
            bankVerified: false,
            loan: undefined,
          };
        }),

      loadScenario: (sc) => {
        const applicant: ApplicantProfile = {
          ...EMPTY_APPLICANT,
          mobileVerified: true,
          financialsLinked: true,
          ...sc.applicant,
        };
        const risk = assessRisk(applicant.monthlySalary, sc.creditScore);
        const sanctioned = sanctionedFromBase(calcEligibleLimit(applicant.monthlySalary), risk);
        const chosen = sc.chosenAmount ?? sanctioned;
        const verified: KycState = { pan: "VERIFIED", aadhaar: "VERIFIED", selfie: "VERIFIED", address: "VERIFIED", bank: "VERIFIED" };

        // States at/after disbursal need a live loan.
        const needsLoan = sc.status === "ACTIVE" || sc.status === "OVERDUE" || sc.status === "REPAID";
        const loan = needsLoan ? buildLoan(chosen, applicant.salaryDay, sc.daysPastDue ?? 0) : undefined;

        set({
          status: sc.forceDecline ? "DECLINED" : sc.status,
          declineReason: sc.forceDecline,
          applicant,
          creditScore: sc.creditScore,
          riskCategory: risk,
          coApplicantRequired: requiresCoApplicant(risk),
          kyc: sc.status === "NEW" || sc.status === "APPLIED" ? { ...FRESH_KYC } : verified,
          chosenAmount: chosen,
          documentsSigned: needsLoan || sc.status === "BANK_VERIFIED" || sc.status === "DOCS_SIGNED",
          bankVerified: needsLoan || sc.status === "BANK_VERIFIED",
          loan,
        });
      },

      reset: () =>
        set({
          status: "NEW",
          applicant: { ...EMPTY_APPLICANT },
          kyc: { ...FRESH_KYC },
          riskCategory: "B",
          creditScore: 0,
          coApplicantRequired: false,
          declineReason: undefined,
          chosenAmount: 0,
          documentsSigned: false,
          bankVerified: false,
          loan: undefined,
        }),
    }),
    {
      name: "navix-borrower-journey",
      version: 3,
      // Older persisted shapes predate fields like `applicant.aadhaar`. Merge any
      // saved values onto the current defaults so that (a) every field is always
      // present — a missing one would flip an input from uncontrolled to
      // controlled — and (b) zustand doesn't log "couldn't be migrated (no migrate
      // function)" whenever the version is bumped. Works for any prior version.
      migrate: (persisted) => {
        const p = (persisted ?? {}) as Partial<BorrowerJourneyState>;
        return {
          ...p,
          applicant: { ...EMPTY_APPLICANT, ...(p.applicant ?? {}) },
          kyc: { ...FRESH_KYC, ...(p.kyc ?? {}) },
        } as BorrowerJourneyState;
      },
    },
  ),
);
