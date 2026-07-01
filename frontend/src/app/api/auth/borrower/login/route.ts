import { NextResponse, type NextRequest } from "next/server";
import { setBorrowerSession } from "@/lib/api/bff-session";
import { config } from "@/lib/config";

/**
 * Borrower login (mobile + OTP). SEPARATE from staff auth — never shares a
 * session/cookie.
 *
 * POST `{ mobile, otp, name?, customerId? }` -> authenticates against the
 * backend `POST /api/auth/borrower/login` (the backend enforces the OTP and
 * derives the customer id). On success the JWT is stored in the httpOnly
 * `navix_borrower` cookie (`{ token, id, customerId, name, mobile }`); the
 * response body omits the token.
 */

interface BorrowerLoginData {
  token: string;
  id: string | number;
  name: string;
  role: string;
  customerId: number;
}

export async function POST(req: NextRequest) {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "Invalid JSON body." }, { status: 400 });
  }

  const { mobile, otp, customerId, name } = (body ?? {}) as {
    mobile?: unknown;
    otp?: unknown;
    customerId?: unknown;
    name?: unknown;
  };

  const cleanMobile = typeof mobile === "string" ? mobile.replace(/\D/g, "") : "";
  if (cleanMobile.length < 10) {
    return NextResponse.json({ error: "Enter a valid 10-digit mobile number." }, { status: 400 });
  }

  let backendRes: Response;
  try {
    backendRes = await fetch(`${config.backendBaseUrl}/api/auth/borrower/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({
        mobile: cleanMobile,
        otp: typeof otp === "string" ? otp : "",
        customerId: typeof customerId === "number" ? customerId : undefined,
        name: typeof name === "string" && name.trim() ? name.trim() : undefined,
      }),
      cache: "no-store",
    });
  } catch {
    return NextResponse.json({ error: "Could not reach the authentication service." }, { status: 502 });
  }

  const text = await backendRes.text();
  // On failure (e.g. INVALID_OTP), pass the backend envelope straight through.
  if (!backendRes.ok) {
    // Propagate the backend's correlation id so the sign-in error can show a ref that greps to logs.
    const headers: Record<string, string> = {
      "Content-Type": backendRes.headers.get("Content-Type") ?? "application/json",
    };
    const rid = backendRes.headers.get("X-Request-Id");
    if (rid) headers["X-Request-Id"] = rid;
    return new NextResponse(text, { status: backendRes.status, headers });
  }

  let data: BorrowerLoginData;
  try {
    data = (JSON.parse(text) as { data: BorrowerLoginData }).data;
  } catch {
    return NextResponse.json({ error: "Unexpected authentication response." }, { status: 502 });
  }

  const session = {
    token: data.token,
    id: String(data.id),
    customerId: data.customerId,
    name: data.name,
    mobile: cleanMobile,
  };
  await setBorrowerSession(session);
  // Never return the token to the browser.
  return NextResponse.json({
    id: session.id,
    customerId: session.customerId,
    name: session.name,
    mobile: session.mobile,
  });
}
