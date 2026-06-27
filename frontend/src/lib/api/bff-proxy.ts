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

  const headers: Record<string, string> = {
    Accept: "application/json",
    Authorization: `Bearer ${token}`,
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
      { status: 502 },
    );
  }

  const text = await backendRes.text();
  // Pass the backend body through unchanged; default to JSON content-type.
  return new NextResponse(text, {
    status: backendRes.status,
    headers: {
      "Content-Type": backendRes.headers.get("Content-Type") ?? "application/json",
    },
  });
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
