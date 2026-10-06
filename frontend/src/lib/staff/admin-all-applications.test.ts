import { describe, expect, it } from "vitest";
import { ApplicationApiError, type AdminApplicationView } from "@/lib/api/applications";
import {
  filterAdminApplications,
  isAdminApplicationsFilterActive,
  retryUnlessClientError,
} from "./admin-all-applications";

function app(overrides: Partial<AdminApplicationView> = {}): AdminApplicationView {
  return {
    id: 1,
    customerId: 10,
    status: "DRAFT",
    amountRequestedPaise: null,
    eligibleLimitPaise: null,
    purpose: null,
    salaryCreditDay: null,
    assignedExecutiveId: null,
    loanId: null,
    hasProfile: true,
    fullName: null,
    pan: null,
    mobile: null,
    email: null,
    dob: null,
    address: null,
    employer: null,
    employmentStatus: null,
    monthlySalaryPaise: null,
    salaryBank: null,
    creditScore: null,
    starRating: null,
    recommendation: null,
    riskCategory: null,
    stepsCompleted: 0,
    stepsRequired: 5,
    agreementAccepted: false,
    complete: false,
    kycCapturedAt: null,
    bureauState: "NOT_FETCHED",
    ...overrides,
  };
}

const ROWS = [
  app({ id: 1, customerId: 10, fullName: "Asha Rao", pan: "ABCDE1234F", mobile: "9876543210", complete: true, status: "SANCTIONED" }),
  app({ id: 2, customerId: 20, fullName: "Ravi Kumar", email: "ravi@example.com", complete: false, status: "KYC_PENDING" }),
  app({ id: 33, customerId: 30, fullName: null, complete: false, status: "DRAFT" }),
];

describe("filterAdminApplications", () => {
  it("returns every row for ALL and a blank search", () => {
    expect(filterAdminApplications(ROWS, "ALL", "").map((a) => a.id)).toEqual([1, 2, 33]);
    expect(filterAdminApplications(ROWS, "ALL", "   ").map((a) => a.id)).toEqual([1, 2, 33]);
  });

  it("keeps only complete or only incomplete rows", () => {
    expect(filterAdminApplications(ROWS, "COMPLETE", "").map((a) => a.id)).toEqual([1]);
    expect(filterAdminApplications(ROWS, "INCOMPLETE", "").map((a) => a.id)).toEqual([2, 33]);
  });

  it("searches name, PAN, mobile, email and ids case-insensitively, trimmed", () => {
    expect(filterAdminApplications(ROWS, "ALL", "  asha ").map((a) => a.id)).toEqual([1]);
    expect(filterAdminApplications(ROWS, "ALL", "abcde1234f").map((a) => a.id)).toEqual([1]);
    expect(filterAdminApplications(ROWS, "ALL", "98765").map((a) => a.id)).toEqual([1]);
    expect(filterAdminApplications(ROWS, "ALL", "RAVI@").map((a) => a.id)).toEqual([2]);
    expect(filterAdminApplications(ROWS, "ALL", "33").map((a) => a.id)).toEqual([33]);
    // Customer id 30 — and nothing else contains "30".
    expect(filterAdminApplications(ROWS, "ALL", "30").map((a) => a.id)).toEqual([33]);
  });

  it("matches the human status label, not only the enum", () => {
    expect(filterAdminApplications(ROWS, "ALL", "kyc pending").map((a) => a.id)).toEqual([2]);
  });

  it("applies the completeness filter and the search together", () => {
    expect(filterAdminApplications(ROWS, "COMPLETE", "ravi")).toEqual([]);
    expect(filterAdminApplications(ROWS, "INCOMPLETE", "ravi").map((a) => a.id)).toEqual([2]);
  });

  it("does not mutate its input", () => {
    const input = [...ROWS];
    filterAdminApplications(input, "COMPLETE", "asha");
    expect(input).toEqual(ROWS);
  });
});

describe("isAdminApplicationsFilterActive", () => {
  it("is false only for ALL with a blank search", () => {
    expect(isAdminApplicationsFilterActive("ALL", "")).toBe(false);
    expect(isAdminApplicationsFilterActive("ALL", "  ")).toBe(false);
    expect(isAdminApplicationsFilterActive("ALL", "asha")).toBe(true);
    expect(isAdminApplicationsFilterActive("COMPLETE", "")).toBe(true);
    expect(isAdminApplicationsFilterActive("INCOMPLETE", "")).toBe(true);
  });
});

describe("retryUnlessClientError", () => {
  it("never retries a 4xx", () => {
    expect(retryUnlessClientError(0, new ApplicationApiError("no", "FORBIDDEN_ROLE", 403))).toBe(false);
    expect(retryUnlessClientError(0, new ApplicationApiError("no", "HTTP_401", 401))).toBe(false);
    expect(retryUnlessClientError(0, new ApplicationApiError("bad", "BAD_REQUEST", 400))).toBe(false);
    expect(retryUnlessClientError(0, new ApplicationApiError("x", "HTTP_499", 499))).toBe(false);
  });

  it("retries a 5xx or a network failure once", () => {
    const server = new ApplicationApiError("down", "HTTP_503", 503);
    const network = new ApplicationApiError("offline", "NETWORK_ERROR", 0);
    expect(retryUnlessClientError(0, server)).toBe(true);
    expect(retryUnlessClientError(1, server)).toBe(false);
    expect(retryUnlessClientError(0, network)).toBe(true);
    expect(retryUnlessClientError(1, network)).toBe(false);
  });

  it("retries an unknown error once", () => {
    expect(retryUnlessClientError(0, new Error("boom"))).toBe(true);
    expect(retryUnlessClientError(1, new Error("boom"))).toBe(false);
  });
});
