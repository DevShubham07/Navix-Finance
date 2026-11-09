import { ApplicationApiError } from "@/lib/api/applications";
import { currentActingRole } from "@/lib/auth/working-role";

/** Close-applications switch (ADMIN). `closed` = no new applications or reloans. */
export type IntakeSwitchStatus = { closed: boolean; updatedBy: string | null; updatedAt: string | null };

const PATH = "/api/staff/applications-closed";

async function call(method: "GET" | "PUT", body?: unknown): Promise<IntakeSwitchStatus> {
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
    | { success?: boolean; data?: IntakeSwitchStatus; error?: { code?: string; message?: string }; message?: string }
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

export const intakeSwitchApi = {
  status: () => call("GET"),
  setClosed: (closed: boolean) => call("PUT", { closed }),
};
