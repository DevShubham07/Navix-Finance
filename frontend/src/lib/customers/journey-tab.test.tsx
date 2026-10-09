import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { staffApi, type ApplicationView, type EventView } from "@/lib/api/applications";
import { JourneyTab } from "@/components/staff/customer-360/journey-tab";

vi.mock("@/components/staff/event-timeline", () => ({
  EventTimeline: ({ events }: { events: EventView[] }) => <div data-testid="timeline">{events.length}</div>,
}));
vi.mock("@/components/staff/journey-stepper", () => ({ JourneyStepper: () => <div /> }));
vi.mock("@/components/staff/journey-assignee", () => ({ JourneyAssignee: () => <div /> }));
vi.mock("@/components/staff/stage-detail-dialog", () => ({ StageDetailDialog: () => null }));

const ev = (id: number, action: string, toStatus: string): EventView =>
  ({ id, action, toStatus, fromStatus: null, actorId: 1, actorRole: "ADMIN", actorName: "A", notes: null, at: "2026-10-08T10:00:00Z" }) as EventView;

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("Journey tab", () => {
  it("counts events per chip and filters the timeline", async () => {
    vi.spyOn(staffApi, "events").mockResolvedValue([
      ev(1, "SUBMIT_KYC", "KYC_PENDING"),
      ev(2, "ASSIGN", "CREDIT_EXEC_PENDING"),
      ev(3, "SANCTION", "SANCTIONED"),
    ]);
    const app = { id: 318, status: "SANCTIONED" } as unknown as ApplicationView;
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <JourneyTab app={app} applicationId={318} />
      </QueryClientProvider>,
    );
    expect(await screen.findByRole("button", { name: "All 3" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Lifecycle 1" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Assignment 1" })).toBeInTheDocument();
    expect(screen.getByTestId("timeline")).toHaveTextContent("3");
    fireEvent.click(screen.getByRole("button", { name: "Decision 1" }));
    expect(screen.getByTestId("timeline")).toHaveTextContent("1");
  });
});
