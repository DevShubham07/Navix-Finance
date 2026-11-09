import { ApplicationApiError } from "@/lib/api/applications";
import { currentActingRole } from "@/lib/auth/working-role";

/**
 * Customer data export (ADMIN) — the browser side of `/api/reports/customer-export/*`. A tiny fetch
 * helper of its own (like `intake-switch.ts`) so the export does not touch the shared API client.
 * The server only ever returns ids and one page of rows at a time; the files are built in the browser.
 */

/** Cell types the server declares. TEXTNUM = digit strings (Aadhaar, account, mobile) that must stay text. */
export type ExportFieldType = "TEXT" | "TEXTNUM" | "NUMBER" | "MONEY" | "DATE" | "DATETIME" | "BOOL";

export type ExportField = {
  key: string;
  label: string;
  group: string;
  type: ExportFieldType;
  /** A full ID number: only served when "Include full ID numbers" is ticked. */
  sensitive: boolean;
};

export type ExportCatalog = { groups: string[]; fields: ExportField[] };

export type ExportGrain = "customer" | "application";

export type ExportMatchRequest = {
  from?: string;
  to?: string;
  /** "all" or a CustomerBookQuery segment key. */
  segment: string;
  /** DPD bucket keys (UPCOMING, T0_T7, …); empty = any. */
  dpd: string[];
  grain: ExportGrain;
};

export type ExportMatch = { count: number; customers: number; ids: number[]; truncated: boolean };

export type ExportCell = string | number | boolean | null;
export type ExportColumn = { key: string; label: string; type: ExportFieldType };
export type ExportRows = { columns: ExportColumn[]; rows: ExportCell[][] };

const BASE = "/api/staff/reports/customer-export";

/** Pages are fetched one at a time; a busy server (two exports already running) is retried with backoff. */
const BUSY_RETRIES = 6;
const sleep = (ms: number, signal?: AbortSignal) =>
  new Promise<void>((resolve, reject) => {
    if (signal?.aborted) return reject(new DOMException("Cancelled", "AbortError"));
    const t = setTimeout(resolve, ms);
    signal?.addEventListener(
      "abort",
      () => {
        clearTimeout(t);
        reject(new DOMException("Cancelled", "AbortError"));
      },
      { once: true },
    );
  });

async function call<T>(method: "GET" | "POST", path: string, body: unknown, signal?: AbortSignal): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    const acting = currentActingRole();
    const res = await fetch(`${BASE}/${path}`, {
      method,
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
        ...(acting ? { "X-Acting-Role": acting } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      credentials: "same-origin",
      cache: "no-store",
      signal,
    });
    const json = (await res.json().catch(() => undefined)) as
      | { success?: boolean; data?: T; error?: { code?: string; message?: string }; message?: string }
      | undefined;
    if (res.ok && json && json.success !== false && json.data !== undefined) return json.data;
    const code = json?.error?.code ?? `HTTP_${res.status}`;
    if (code === "EXPORT_BUSY" && attempt < BUSY_RETRIES) {
      await sleep(Math.min(1000 * 2 ** attempt, 8000), signal);
      continue;
    }
    throw new ApplicationApiError(
      json?.error?.message ?? json?.message ?? `Request failed with status ${res.status}.`,
      code,
      res.status,
    );
  }
}

export const customerExportApi = {
  fields: () => call<ExportCatalog>("GET", "fields", undefined),
  match: (req: ExportMatchRequest, signal?: AbortSignal) => call<ExportMatch>("POST", "match", req, signal),
  rows: (
    req: { grain: ExportGrain; ids: number[]; fields: string[]; includeIdNumbers: boolean },
    signal?: AbortSignal,
  ) => call<ExportRows>("POST", "rows", req, signal),
};
