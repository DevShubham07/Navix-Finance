/**
 * Shared proxy logic for the BFF catch-all route handlers. Forwards a request
 * to the Spring backend (`config.backendBaseUrl`), attaching the caller's JWT
 * (held server-side in the session cookie) as an `Authorization: Bearer` header.
 *
 * The backend authenticates every `/api/**` call (except `/api/auth/**`,
 * `/api/storage/**`, docs) from this bearer.
 */

import { NextResponse, type NextRequest } from "next/server";
import { config } from "@/lib/config";

/**
 * Proxy `req` to `${backendBaseUrl}${backendPath}${search}`, forwarding the
 * caller's bearer token. Returns the backend status + JSON verbatim so the typed
 * client can unwrap the ApiResponse envelope (including error.code).
 */
export async function proxyToBackend(
  req: NextRequest,
  backendPath: string,
  token: string,
): Promise<NextResponse> {
  const search = req.nextUrl.search; // includes leading "?" or ""
  const url = `${config.backendBaseUrl}${backendPath}${search}`;

  // Cross-tier correlation: reuse an inbound X-Request-Id (browser) or mint one, forward it to the
  // backend (RequestLoggingFilter honors + echoes it), and return it to the browser on every path so
  // a UI error can quote a ref that greps straight to the backend access + business-rejection lines.
  const requestId = newRequestId(req);
  const headers: Record<string, string> = {
    Accept: "application/json",
    Authorization: `Bearer ${token}`,
    "X-Request-Id": requestId,
  };

  let body: string | undefined;
  if (req.method !== "GET" && req.method !== "HEAD") {
    const raw = await req.text();
    body = raw || undefined;
    if (body) headers["Content-Type"] = "application/json";
  }

  let backendRes: Response;
  try {
    backendRes = await fetch(url, {
      method: req.method,
      headers,
      body,
      cache: "no-store",
    });
  } catch (e) {
    return NextResponse.json(
      {
        success: false,
        message: "Could not reach the backend service.",
        data: null,
        error: {
          code: "BACKEND_UNREACHABLE",
          message: e instanceof Error ? e.message : "Backend unreachable.",
        },
        timestamp: new Date().toISOString(),
      },
      // Stamp the BFF-generated id even when the backend was never reached.
      { status: 502, headers: { "X-Request-Id": requestId } },
    );
  }

  const text = await backendRes.text();
  // Pass the backend body through unchanged; default to JSON content-type. Propagate the backend's
  // echoed X-Request-Id (falling back to the id generated for this hop) so the client can surface it.
  return new NextResponse(text, {
    status: backendRes.status,
    headers: {
      "Content-Type": backendRes.headers.get("Content-Type") ?? "application/json",
      "X-Request-Id": backendRes.headers.get("X-Request-Id") ?? requestId,
    },
  });
}

/** Reuse an inbound `X-Request-Id` (from the browser) or mint a fresh one for this hop. */
export function newRequestId(req: Request): string {
  return req.headers.get("x-request-id") ?? crypto.randomUUID();
}

/**
 * Copy the backend's echoed `X-Request-Id` onto an outgoing header map (falling back to the id
 * generated for this hop). Used by the auth route handlers, which fetch the backend directly rather
 * than through {@link proxyToBackend}.
 */
export function copyRequestId(backendRes: Response, headers: Record<string, string>, id: string): void {
  headers["X-Request-Id"] = backendRes.headers.get("X-Request-Id") ?? id;
}

/** Join the catch-all `[...path]` segments into a backend path suffix. */
export function joinPath(segments: string[] | undefined): string {
  if (!segments || segments.length === 0) return "";
  return segments.map(encodeURIComponent).join("/");
}

/** Envelope-shaped 401 for an unauthenticated proxy call. */
export function unauthorized(message: string): NextResponse {
  return NextResponse.json(
    {
      success: false,
      message,
      data: null,
      error: { code: "UNAUTHENTICATED", message },
      timestamp: new Date().toISOString(),
    },
    { status: 401 },
  );
}

/** Envelope-shaped 403 for a proxy call the session is not allowed to make. */
export function forbidden(message: string): NextResponse {
  return NextResponse.json(
    {
      success: false,
      message,
      data: null,
      error: { code: "FORBIDDEN", message },
      timestamp: new Date().toISOString(),
    },
    { status: 403 },
  );
}
