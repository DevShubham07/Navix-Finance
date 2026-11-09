import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { customersApi, type AnalyzerGroup, type AnalyzerView } from "@/lib/api/applications";
import { AnalyzerTab } from "@/components/staff/customer-360/analyzer-tab";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const group = (key: string, loans: number, bad: number): AnalyzerGroup => ({
  key, loans, customers: loans, bad, principalPaise: loans * 500_000, badPrincipalPaise: bad * 500_000,
  paidOutPaise: loans * 450_000, collectedPaise: (loans - bad) * 600_000, zeroPaidBad: 1,
});

function response(over: Partial<AnalyzerView>): AnalyzerView {
  return {
    asOf: "2026-11-09",
    mode: "NO_DATA",
    band: null,
    concernCount: 0,
    unknownCount: 0,
    pointers: [],
    informational: [],
    facts: { salaryPaise: null, totalAccounts: null, employedAtDeclared: null, score: null, stars: null, recommendation: null, riskCategory: null },
    ownRecord: null,
    incomeUncorroborated: false,
    benchmarks: {
      recentStart: "2026-09-01",
      all: group("ALL", 60, 20), earlyFirst: group("EARLY_FIRST", 20, 5), recentFirst: group("RECENT_FIRST", 30, 12), repeat: group("REPEAT", 10, 3),
      breakEvenBps: 4200,
      patterns: [{ pattern: "ALWAYS_PAID", customers: 7 }],
      concerns: [], informational: [],
      pointTable: [{ points: 0, loans: 5, bad: 1, zeroPaid: 0 }],
      bands: [{ band: "HIGH", loans: 25, bad: 12 }],
      incomeUncorroborated: { loans: 4, bad: 3, zeroPaid: 2 },
      repeatEarlyPayer: { earlyLoans: 3, earlyBad: 0, otherLoans: 7, otherBad: 3 },
    },
    ...over,
  };
}

function renderTab(data: AnalyzerView) {
  vi.spyOn(customersApi, "analyzer").mockResolvedValue(data);
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <AnalyzerTab customerId={42} />
    </QueryClientProvider>,
  );
}

describe("AI Analyzer tab", () => {
  it("shows the HIGH band, a pointer's exact counts and the disclaimer", async () => {
    renderTab(response({
      mode: "FIRST_LOAN",
      band: "HIGH",
      concernCount: 3,
      unknownCount: 1,
      incomeUncorroborated: true,
      facts: { salaryPaise: 6_000_000, totalAccounts: 12, employedAtDeclared: false, score: 640, stars: 2, recommendation: "NOT RECOMMEND", riskCategory: "C" },
      pointers: [
        { key: "THIN_RECORD", state: "CONCERN", value: 12, withLoans: 27, withBad: 16, withoutLoans: 15, withoutBad: 3 },
        { key: "LONG_WAIT", state: "UNKNOWN", value: null, withLoans: 10, withBad: 5, withoutLoans: 20, withoutBad: 4 },
      ],
    }));
    expect(await screen.findByText("High risk")).toBeInTheDocument();
    expect(screen.getByText("3 of 4 concerns")).toBeInTheDocument();
    expect(screen.getByText("Provisional: 1 still not known")).toBeInTheDocument();
    expect(screen.getByTitle("With this sign: 16 of 27 went bad. Without: 3 of 15.")).toBeInTheDocument();
    expect(screen.getByText(/With: 59% \(16 of 27 loans\)/)).toBeInTheDocument();
    expect(screen.getByText(/Rule-based summary of past DhanBoost loans, not AI/)).toBeInTheDocument();
    expect(screen.getByText("Declared income not corroborated")).not.toBeNull();
  });

  it("shows No result for a customer with nothing to compare", async () => {
    renderTab(response({}));
    expect(await screen.findByText("No result")).toBeInTheDocument();
    expect(screen.getByText("Nothing to compare")).toBeInTheDocument();
    expect(screen.getByText(/never approves, rejects or blocks a loan/)).toBeInTheDocument();
  });
});
