/**
 * Admin Templates: the three tabs, Send locked to sendable templates, the flag switching everything
 * off, and one variable picker per placeholder on save.
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/lib/api/applications", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api/applications")>("@/lib/api/applications");
  return {
    ...actual,
    storedTemplatesApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), refreshWhatsApp: vi.fn() },
    featureFlagsApi: { get: vi.fn() },
    customersApi: { page: vi.fn() },
    customerMessagesApi: { templates: vi.fn(), preview: vi.fn(), send: vi.fn(), log: vi.fn() },
  };
});
vi.mock("@/components/staff/live-pipeline", () => ({
  PermissionGate: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  NoAccessNotice: () => null,
}));
vi.mock("@/components/staff/staff-ui", () => ({
  PageHeader: ({ title }: { title: string }) => <h1>{title}</h1>,
}));

import * as React from "react";
import { featureFlagsApi, storedTemplatesApi, type StoredTemplate } from "@/lib/api/applications";
import AdminTemplatesPage from "./page";

const list = vi.mocked(storedTemplatesApi.list);
const create = vi.mocked(storedTemplatesApi.create);
const flags = vi.mocked(featureFlagsApi.get);

const row = (over: Partial<StoredTemplate>): StoredTemplate => ({
  id: 1,
  key: "tpl:1",
  channel: "SMS",
  name: "DHANBOOST_A",
  subject: null,
  body: "Dear {#var#}. - DhanBoost",
  category: "SERVICE",
  status: "ACTIVE",
  providerId: "1777179420408031626",
  rejectionReason: null,
  variables: ["name"],
  rule: "LIVE",
  confirmText: null,
  systemManaged: false,
  unsendableReason: null,
  createdByName: "Admin",
  createdAt: "2026-11-09T00:00:00Z",
  updatedAt: "2026-11-09T00:00:00Z",
  ...over,
});

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <AdminTemplatesPage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  flags.mockResolvedValue({ "admin-messaging": true });
  list.mockResolvedValue([]);
});

describe("Admin Templates", () => {
  it("has the three channel tabs and loads each channel", async () => {
    renderPage();
    await waitFor(() => expect(list).toHaveBeenCalledWith("SMS"));
    for (const name of ["SMS", "WhatsApp", "Email"]) expect(screen.getByRole("tab", { name })).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: "WhatsApp" }));
    await waitFor(() => expect(list).toHaveBeenCalledWith("WHATSAPP"));
    expect(screen.getByRole("button", { name: /Refresh status from SmartChat/ })).toBeTruthy();
  });

  it("shows status and locks Send on an unsendable template", async () => {
    list.mockResolvedValue([
      row({}),
      row({ id: 2, name: "DHANBOOST_B", status: "SUBMITTED", providerId: null, unsendableReason: "Only an Active template can be sent." }),
    ]);
    renderPage();
    const buttons = await screen.findAllByRole("button", { name: "Send" });
    expect((buttons[0] as HTMLButtonElement).disabled).toBe(false);
    expect((buttons[1] as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText("Submitted")).toBeTruthy();
    expect(screen.getByText("Only an Active template can be sent.")).toBeTruthy();
  });

  it("disables add and send while the flag is off", async () => {
    flags.mockResolvedValue({ "admin-messaging": false });
    list.mockResolvedValue([row({})]);
    renderPage();
    const send = await screen.findByRole("button", { name: "Send" });
    await waitFor(() => expect((send as HTMLButtonElement).disabled).toBe(true));
    expect((screen.getByRole("button", { name: /Add template/ }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("saves one variable per placeholder", async () => {
    create.mockResolvedValue(row({}));
    renderPage();
    const add = await screen.findByRole("button", { name: /Add template/ });
    await waitFor(() => expect((add as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(add);
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "NEW_T" } });
    fireEvent.change(screen.getByLabelText("Wording"), { target: { value: "Dear {#var#}, pay {#var#}. - DhanBoost" } });
    fireEvent.change(screen.getByLabelText("Variable 2"), { target: { value: "amount" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(create).toHaveBeenCalled());
    expect(create.mock.calls[0][0]).toMatchObject({ channel: "SMS", name: "NEW_T", variables: ["name", "amount"] });
  });
});
