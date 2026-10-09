import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  skipTraceApi,
  staffApi,
  type ApplicationView,
  type CustomerDetail,
  type StepResult,
} from "@/lib/api/applications";
import { AddressesTab } from "@/components/staff/customer-360/addresses-tab";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("Addresses tab", () => {
  it("renders the declared and Aadhaar cards and the skip-trace empty state", async () => {
    vi.spyOn(staffApi, "verifications").mockResolvedValue([
      {
        checkType: "AADHAAR",
        status: "PASS",
        message: null,
        derived: { address: "12 MG Road, Pune", city: "Pune", pincode: "411001" },
      } as StepResult,
    ]);
    vi.spyOn(skipTraceApi, "history").mockResolvedValue([]);
    const app = { id: 318 } as unknown as ApplicationView;
    const detail = { customerId: 42, profile: { address: "Flat 4, Baner" }, applications: [app], loans: [] } as unknown as CustomerDetail;
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <AddressesTab detail={detail} customerId={42} applicationId={318} app={app} onChanged={() => {}} />
      </QueryClientProvider>,
    );
    expect(screen.getByText("Flat 4, Baner")).toBeInTheDocument();
    expect(await screen.findByText("12 MG Road, Pune")).toBeInTheDocument();
    expect(screen.getByText("411001")).toBeInTheDocument();
    expect(await screen.findByText("No skip trace has been run")).toBeInTheDocument();
    expect(screen.getByText("Not run")).toBeInTheDocument();
  });
});
