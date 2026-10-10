import { cleanup, render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  customersApi,
  staffApi,
  type ApplicationView,
  type CreditBriefView,
  type CustomerDetail,
  type StepResult,
} from "@/lib/api/applications";
import { bankAnalysisApi } from "@/lib/api/bank-analysis";
import type { JsonValue } from "@/lib/credit/provider-report";
import { CustomerTabBody } from "@/components/staff/customer-tabs";
import kba from "@/lib/customers/bre/__fixtures__/crif-auth-answer-sample.json";

vi.mock("@/lib/auth/staff-session", () => ({
  useStaffSession: () => ({ session: { role: "ADMIN" }, loading: false }),
}));
vi.mock("@/components/staff/live-pipeline", () => ({
  PermissionGate: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  errMessage: (e: unknown) => String(e),
}));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const steps = [
  { checkType: "PAN", status: "PASS", message: null, derived: { fullName: "SAMPLE PERSON", panNumber: "AAAPS0000A", aadhaarLinked: true } },
  { checkType: "EMPLOYMENT", status: "PASS", message: null, derived: { found: true, employed: true, uan: "100000000001", employerName: "INDIAN ARMY" } },
] as StepResult[];

function renderBre(opts: { applicationId?: number | null; brief?: Partial<CreditBriefView> } = {}) {
  vi.spyOn(staffApi, "verifications").mockResolvedValue(steps);
  vi.spyOn(staffApi, "creditBrief").mockResolvedValue({
    applicationId: 318, creditScore: 510, bureauState: "FOUND", bureauSource: "FINTRIX_CRIF",
    generatedAt: "2026-08-24T10:00:00Z", providerResponse: kba as unknown as JsonValue, facts: null, ...opts.brief,
  } as CreditBriefView);
  vi.spyOn(customersApi, "documents").mockResolvedValue([
    { applicationId: 318, applicationStatus: "CREDIT_EXEC_PENDING", documents: [{ id: 5, docType: "SALARY_SLIP", fileName: "s.pdf", contentType: "application/pdf", sizeBytes: 1, uploadedAt: "2026-08-01T00:00:00Z" }] },
  ] as never);
  vi.spyOn(bankAnalysisApi, "get").mockResolvedValue(null);
  const app = { id: 318, status: "CREDIT_EXEC_PENDING" } as unknown as ApplicationView;
  const detail = {
    customerId: 42,
    profile: { fullName: "SAMPLE PERSON", employmentStatus: "SALARIED", employer: "Indian Army", monthlySalaryPaise: 4_000_000, aadhaar: "234567891234" },
    applications: opts.applicationId === null ? [] : [app],
    loans: [],
    payments: [],
  } as unknown as CustomerDetail;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <CustomerTabBody tab="bre" detail={detail} customerId={42} app={opts.applicationId === null ? null : app} applicationId={opts.applicationId === undefined ? 318 : opts.applicationId} />
    </QueryClientProvider>,
  );
}

describe("BRE tab", () => {
  it("lists all nine rules with a validated / flagged result each", async () => {
    renderBre();
    expect(await screen.findByText("Business rule engine")).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Business rules overview" });
    expect(within(table).getAllByRole("row")).toHaveLength(10); // header + 9 rules
    expect(screen.getByText(/^\d\/9 validated$/)).toBeInTheDocument();
    expect(screen.getByText(/Flags only — these rules never reject or block a loan/)).toBeInTheDocument();
  });

  it("flags the low CRIF score, the NBFC loan 900 days past due and the army employer", async () => {
    renderBre();
    await screen.findByText("Business rule engine");
    expect(screen.getAllByText(/CRIF score 510/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/900 DPD/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Army \/ armed forces/).length).toBeGreaterThan(0);
  });

  it("shows the CRIF report analysis inside the occupation rule and a salary-slip button to open", async () => {
    renderBre();
    await screen.findByText("Business rule engine");
    expect(screen.getByText("CRIF report analysis")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /Salary slip/ }).length).toBeGreaterThan(0);
  });

  it("says there is no application for a lead-only customer", () => {
    renderBre({ applicationId: null });
    expect(screen.getByText("No application yet")).toBeInTheDocument();
  });
});
