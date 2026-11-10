import { ApplicationApiError } from "@/lib/api/applications";
import { currentActingRole } from "@/lib/auth/working-role";

/**
 * Payment-gateway switch (ADMIN). `enabled` = borrowers pay through PayU online checkout; off = the manual
 * repay screen (UPI / bank details + screenshot). `configured` = whether turning it on would work here.
 */
export type PaymentGatewayStatus = {
  enabled: boolean;
  configured: boolean;
  updatedBy: string | null;
  updatedAt: string | null;
};

const PATH = "/api/staff/payment-gateway";

async function call(method: "GET" | "PUT", body?: unknown): Promise<PaymentGatewayStatus> {
  const acting = currentActingRole();
  const res = await fetch(PATH, {
    method,
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
      ...(acting ? { "X-Acting-Role": acting } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
    credentials: "same-origin",
    cache: "no-store",
  });
  const json = (await res.json().catch(() => undefined)) as
    | { success?: boolean; data?: PaymentGatewayStatus; error?: { code?: string; message?: string }; message?: string }
    | undefined;
  if (!res.ok || !json || json.success === false || !json.data) {
    throw new ApplicationApiError(
      json?.error?.message ?? json?.message ?? `Request failed with status ${res.status}.`,
      json?.error?.code ?? `HTTP_${res.status}`,
      res.status,
    );
  }
  return json.data;
}

export const paymentGatewayApi = {
  status: () => call("GET"),
  setEnabled: (enabled: boolean) => call("PUT", { enabled }),
};
