/**
 * User-facing error formatting for the whole app.
 *
 * Every place that shows an API error should render it through here so the message
 * always carries the backend error `code` and the cross-tier `requestId` (from the
 * `X-Request-Id` header) — something the user can quote in support and that greps
 * straight to the backend logs (`req=<id>`).
 */

import { ApplicationApiError } from "@/lib/api/applications";

/** Append ` (CODE)` and, when present, ` · ref <requestId>` to a message. */
function decorate(message: string, code: string, requestId?: string): string {
  const base = `${message} (${code})`;
  return requestId ? `${base} · ref ${requestId}` : base;
}

/**
 * Render any thrown value as a single user-facing string. For an
 * {@link ApplicationApiError} (everything from the typed client) this includes the
 * code + request-id; anything else falls back to its message or {@code fallback}.
 */
export function formatApiError(e: unknown, fallback = "Something went wrong — please try again."): string {
  if (e instanceof ApplicationApiError) {
    return decorate(e.message, e.code, e.requestId);
  }
  return e instanceof Error ? e.message : fallback;
}

/** Normalized error read from a raw `fetch` Response (auth pages call the BFF directly). */
export interface EnvelopeError {
  message: string;
  code: string;
  requestId?: string;
}

/**
 * Read a NAVIX `ApiResponse` error envelope + the `X-Request-Id` header from a raw
 * `fetch` Response (the auth pages bypass the typed client). Returns a normalized
 * `{message, code, requestId}` even if the body isn't the expected shape.
 */
export async function readEnvelopeError(
  res: Response,
  fallback = "Request failed — please try again.",
): Promise<EnvelopeError> {
  const requestId = res.headers.get("X-Request-Id") ?? undefined;
  let message = fallback;
  let code = `HTTP_${res.status}`;
  try {
    const env = (await res.json()) as
      | { error?: { code?: string; message?: string } | string; message?: string }
      | null;
    if (env && typeof env.error === "object" && env.error) {
      message = env.error.message ?? env.message ?? fallback;
      code = env.error.code ?? code;
    } else if (env && typeof env.error === "string") {
      message = env.error;
    } else if (env?.message) {
      message = env.message;
    }
  } catch {
    /* non-JSON body — keep the fallback message + HTTP-derived code */
  }
  return { message, code, requestId };
}

/** Format an {@link EnvelopeError} the same way as {@link formatApiError}. */
export function formatEnvelopeError(e: EnvelopeError): string {
  return decorate(e.message, e.code, e.requestId);
}
