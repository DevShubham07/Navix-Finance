import { NextResponse, type NextRequest } from "next/server";
import { setBorrowerSession } from "@/lib/api/bff-session";
import { config } from "@/lib/config";

/**
 * Borrower login (mobile + OTP). SEPARATE from staff auth — never shares a
 * session/cookie.
 *
 * POST `{ mobile, otp, name?, applicantId? }` -> authenticates against the
 * backend `POST /api/auth/borrower/login` (the backend enforces the OTP and
 * derives the applicant id). On success the JWT is stored in the httpOnly
 * `navix_borrower` cookie (`{ token, id, applicantId, name, mobile }`); the
 * response body omits the token.
 */

interface BorrowerLoginData {
  token: string;
  id: string | number;
  name: string;
  role: string;
  applicantId: number;
}

export async function POST(req: NextRequest) {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "Invalid JSON body." }, { status: 400 });
  }

  const { mobile, otp, applicantId, name } = (body ?? {}) as {
    mobile?: unknown;
    otp?: unknown;
    applicantId?: unknown;
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
        applicantId: typeof applicantId === "number" ? applicantId : undefined,
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
    return new NextResponse(text, {
      status: backendRes.status,
      headers: { "Content-Type": backendRes.headers.get("Content-Type") ?? "application/json" },
    });
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
    applicantId: data.applicantId,
    name: data.name,
    mobile: cleanMobile,
  };
  await setBorrowerSession(session);
  // Never return the token to the browser.
  return NextResponse.json({
    id: session.id,
    applicantId: session.applicantId,
    name: session.name,
    mobile: session.mobile,
  });
}
