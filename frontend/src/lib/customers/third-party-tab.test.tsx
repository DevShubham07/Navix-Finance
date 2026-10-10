import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { skipTraceApi, staffApi, type ApplicationView, type CustomerDetail, type StepResult } from "@/lib/api/applications";
import { ThirdPartyTab } from "@/components/staff/customer-360/third-party-tab";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function renderTab(steps: StepResult[], applicationId: number | null = 318, profile: Record<string, unknown> = {}) {
  vi.spyOn(staffApi, "verifications").mockResolvedValue(steps);
  vi.spyOn(skipTraceApi, "history").mockResolvedValue([]);
  const app = { id: 318 } as unknown as ApplicationView;
  const detail = { customerId: 42, profile, applications: [app], loans: [] } as unknown as CustomerDetail;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <ThirdPartyTab detail={detail} customerId={42} applicationId={applicationId} app={app} onChanged={() => {}} />
    </QueryClientProvider>,
  );
}

const pan = {
  checkType: "PAN",
  status: "PASS",
  message: null,
  provider: "SIGNZY",
  checkedAt: "2026-10-01T10:00:00Z",
  derived: {
    fullName: "ASHA VERMA",
    panNumber: "ABCDE1234F",
    maskedAadhaar: "XXXXXXXX1234",
    aadhaarLinked: true,
    panStatus: "Valid",
    addressState: "Haryana",
  },
} as StepResult;

describe("Third-party tab", () => {
  it("opens the first row with data and shows presentable PAN cards", async () => {
    renderTab([pan]);
    expect(await screen.findByText("Personal Information")).toBeInTheDocument();
    expect(screen.getByText("Verification Status")).toBeInTheDocument();
    expect(screen.getByText("ASHA VERMA")).toBeInTheDocument();
    expect(screen.getByText("Valid")).toBeInTheDocument();
    expect(screen.getByText("Yes")).toBeInTheDocument();
    expect(screen.getByText("Lead PAN")).toBeInTheDocument();
  });

  it("marks providers without a result as Not run", async () => {
    renderTab([pan]);
    await screen.findByText("Personal Information");
    expect(screen.getAllByText("Not run").length).toBeGreaterThan(5);
    fireEvent.click(screen.getByText("Penny Drop"));
    expect(await screen.findByText("Penny Drop not run")).toBeInTheDocument();
  });

  it("policy: staff see the full Aadhaar from the profile; provider pill is neutral, never red", async () => {
    renderTab([
      { ...pan, checkType: "AADHAAR", derived: { fullName: "ASHA VERMA", maskedAadhaar: "XXXXXXXX1234", pincode: "122001" } } as StepResult,
    ], 318, { aadhaar: "123456781234" });
    expect(await screen.findByText("123456781234")).toBeInTheDocument();
    const provider = screen.getByText("SIGNZY");
    expect(provider.className).toContain("neutral");
    expect(provider.className).not.toContain("error");
  });

  it("falls back to the masked Aadhaar when the profile has none", async () => {
    renderTab([{ ...pan, checkType: "AADHAAR", derived: { maskedAadhaar: "XXXXXXXX1234" } } as StepResult]);
    expect(await screen.findByText("XXXXXXXX1234")).toBeInTheDocument();
  });

  it("raw view shows the account number but hides credential urls", async () => {
    renderTab([{ ...pan, checkType: "PENNY_DROP", derived: { accountNumber: "50100123456789", url: "https://secret.example/x" } } as StepResult]);
    const raw = (await screen.findByText(/"accountNumber"/)).textContent ?? "";
    expect(raw).toContain("50100123456789");
    expect(raw).not.toContain("secret.example");
  });

  it("shows an empty state without an application", () => {
    renderTab([], null);
    expect(screen.getByText("No application yet")).toBeInTheDocument();
  });

  it("renders bureau balance in rupees, penny-drop name match as a percentage, and defers skip trace", async () => {
    renderTab([
      { ...pan, checkType: "BUREAU", derived: { totalBalance: 150000, activeAccounts: 2 } } as StepResult,
      { ...pan, checkType: "PENNY_DROP", derived: { nameMatch: 0.85, accountExists: true } } as StepResult,
    ]);
    expect(await screen.findByText("₹1,50,000")).toBeInTheDocument();
    fireEvent.click(screen.getByText("Penny Drop"));
    const pct = await screen.findByText("85%");
    expect(pct.className).toContain("success");
    expect(skipTraceApi.history).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText("Skip Trace"));
    expect(await screen.findByText(/skip trace/i, { selector: "div,span,p,h3" })).toBeTruthy();
    expect(skipTraceApi.history).toHaveBeenCalled();
  });
  it("derives the Aadhaar–PAN linkage step from the PAN response: both numbers, linked, success", async () => {
    renderTab([pan], 318, { aadhaar: "123456781234" });
    await screen.findByText("Personal Information");
    fireEvent.click(screen.getByText("Aadhaar–PAN Linkage"));
    expect(await screen.findByText("Linked")).toBeInTheDocument();
    expect(screen.getAllByText("123456781234").length).toBeGreaterThan(0);
    expect(screen.getAllByText("ABCDE1234F").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Success").length).toBeGreaterThan(0);
  });

  it("shows the linkage as not linked when the PAN provider says so", async () => {
    renderTab([{ ...pan, derived: { ...pan.derived, aadhaarLinked: false } } as StepResult]);
    await screen.findByText("Personal Information");
    fireEvent.click(screen.getByText("Aadhaar–PAN Linkage"));
    expect(await screen.findByText("Not linked")).toBeInTheDocument();
  });
});
