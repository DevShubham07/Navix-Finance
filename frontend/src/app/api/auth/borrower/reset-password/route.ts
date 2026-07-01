import { NextResponse, type NextRequest } from "next/server";
import { config } from "@/lib/config";

/** Borrower reset-password. POST { token, password } -> backend; passes the envelope straight through. */
export async function POST(req: NextRequest) {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "Invalid JSON body." }, { status: 400 });
  }
  const { token, password } = (body ?? {}) as { token?: unknown; password?: unknown };
  let backendRes: Response;
  try {
    backendRes = await fetch(`${config.backendBaseUrl}/api/auth/borrower/reset-password`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({
        token: typeof token === "string" ? token : "",
        password: typeof password === "string" ? password : "",
      }),
      cache: "no-store",
    });
  } catch {
    return NextResponse.json({ error: "Could not reach the authentication service." }, { status: 502 });
  }
  const text = await backendRes.text();
  // Propagate the backend's correlation id so the UI error can show a ref that greps to logs.
  const headers: Record<string, string> = {
    "Content-Type": backendRes.headers.get("Content-Type") ?? "application/json",
  };
  const rid = backendRes.headers.get("X-Request-Id");
  if (rid) headers["X-Request-Id"] = rid;
  return new NextResponse(text, { status: backendRes.status, headers });
}
