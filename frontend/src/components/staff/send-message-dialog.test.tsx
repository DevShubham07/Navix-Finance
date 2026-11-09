/**
 * `SendMessageDialog`: singleOnly templates hide on a multi-select, `confirmText` gates Send, the
 * percent select is 1..7, and one failed recipient never stops the rest.
 */
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/lib/api/applications", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api/applications")>("@/lib/api/applications");
  return { ...actual, customerMessagesApi: { templates: vi.fn(), preview: vi.fn(), send: vi.fn(), log: vi.fn() } };
});

import { customerMessagesApi, type MessageTemplate } from "@/lib/api/applications";
import { SendMessageDialog } from "@/components/staff/send-message-dialog";

const api = vi.mocked(customerMessagesApi);

const TEMPLATES: MessageTemplate[] = [
  { key: "no_response", label: "No response", category: "SERVICE", channels: ["WHATSAPP"], inputs: [], singleOnly: false, confirmText: null },
  { key: "ptp_confirmation", label: "Promise to pay", category: "SERVICE", channels: ["WHATSAPP"], inputs: [], singleOnly: true, confirmText: null },
  { key: "reported_soft", label: "Reported", category: "SERVICE", channels: ["WHATSAPP"], inputs: [], singleOnly: false, confirmText: "MLB has reported this loan." },
  {
    key: "repay_discount",
    label: "Repay discount",
    category: "MARKETING",
    channels: ["WHATSAPP"],
    inputs: [
      { name: "percent", label: "Discount", type: "PERCENT" },
      { name: "repayBy", label: "Repay by", type: "DATE" },
    ],
    singleOnly: false,
    confirmText: null,
  },
];

const T3 = [
  { customerId: 1, label: "A" },
  { customerId: 2, label: "B" },
  { customerId: 3, label: "C" },
];

function renderDialog(targets = T3) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <SendMessageDialog open onClose={() => {}} targets={targets} />
    </QueryClientProvider>,
  );
}

async function pick(label: string) {
  const sel = await screen.findByLabelText("Template");
  await screen.findByRole("option", { name: new RegExp(`^${label}`) });
  fireEvent.change(sel, { target: { value: TEMPLATES.find((t) => t.label === label)!.key } });
}

const preview = { customerId: 1, recipientName: "A", maskedMobile: "XXXXXX1234", channels: [] };

beforeEach(() => {
  vi.resetAllMocks();
  api.templates.mockResolvedValue(TEMPLATES);
  api.preview.mockResolvedValue(preview);
});

describe("<SendMessageDialog/>", () => {
  it("opens with initialTemplateKey chosen and its channel ticked", async () => {
    api.preview.mockResolvedValue(preview);
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <SendMessageDialog open onClose={() => {}} targets={T3} initialTemplateKey="no_response" />
      </QueryClientProvider>,
    );
    await waitFor(() => expect((screen.getByLabelText("Template") as HTMLSelectElement).value).toBe("no_response"));
    expect((screen.getByRole("checkbox", { name: "WhatsApp" }) as HTMLInputElement).checked).toBe(true);
  });

  it("hides singleOnly templates when more than one target, shows them for one", async () => {
    const { unmount } = renderDialog(T3);
    await screen.findByRole("option", { name: "No response" });
    expect(screen.queryByRole("option", { name: "Promise to pay" })).toBeNull();
    unmount();
    renderDialog(T3.slice(0, 1));
    expect(await screen.findByRole("option", { name: "Promise to pay" })).toBeTruthy();
  });

  it("keeps Send disabled until the confirm box is ticked", async () => {
    renderDialog(T3.slice(0, 1));
    await pick("Reported");
    fireEvent.click(screen.getByRole("button", { name: /^Preview/ }));
    await waitFor(() => expect(screen.getByTestId("message-preview")).toBeTruthy());
    const send = screen.getByRole("button", { name: /^Send$/ });
    expect((send as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("checkbox", { name: /MLB has reported/ }));
    expect((send as HTMLButtonElement).disabled).toBe(false);
  });

  it("offers percent 1 to 7 only", async () => {
    renderDialog(T3.slice(0, 1));
    await pick("Repay discount");
    const sel = screen.getByLabelText("Discount");
    const opts = within(sel).getAllByRole("option").map((o) => o.textContent);
    expect(opts).toEqual(["Select…", "1%", "2%", "3%", "4%", "5%", "6%", "7%"]);
  });

  it("continues after one recipient fails", async () => {
    api.send.mockImplementation(async (b) => {
      if ("customerId" in b.target && b.target.customerId === 2) throw new Error("boom");
      return { customerId: 0, results: [{ channel: "WHATSAPP", status: "SENT", reason: null, messageId: 1 }] };
    });
    renderDialog();
    await pick("No response");
    // A single-channel template pre-ticks its channel.
    fireEvent.click(screen.getByRole("button", { name: /^Preview/ }));
    await waitFor(() => expect(screen.getByTestId("message-preview")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: /^Send$/ }));
    await screen.findByText(/2 of 3 recipients received/);
    expect(api.send).toHaveBeenCalledTimes(3);
    expect(screen.getByText(/Failed: boom/)).toBeTruthy();
  });
});
