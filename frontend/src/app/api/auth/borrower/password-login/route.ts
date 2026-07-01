import { NextResponse, type NextRequest } from "next/server";
import { setBorrowerSession } from "@/lib/api/bff-session";
import { config } from "@/lib/config";

/**
 * Borrower login by password (the OTP-less alternative). POST `{ mobile, password }` ->
 * backend `POST /api/auth/borrower/password-login`; on success the JWT is stored in the httpOnly
 * `navix_borrower` cookie and the token is omitted from the response. Mirrors the OTP login route.
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

  const { mobile, password } = (body ?? {}) as { mobile?: unknown; password?: unknown };
  const cleanMobile = typeof mobile === "string" ? mobile.replace(/\D/g, "") : "";
  if (cleanMobile.length < 10) {
    return NextResponse.json({ error: "Enter a valid 10-digit mobile number." }, { status: 400 });
  }

  let backendRes: Response;
  try {
    backendRes = await fetch(`${config.backendBaseUrl}/api/auth/borrower/password-login`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({ mobile: cleanMobile, password: typeof password === "string" ? password : "" }),
      cache: "no-store",
    });
  } catch {
    return NextResponse.json({ error: "Could not reach the authentication service." }, { status: 502 });
  }

  const text = await backendRes.text();
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
    customerId: data.customerId,
    name: data.name,
    mobile: cleanMobile,
  };
  await setBorrowerSession(session);
  return NextResponse.json({
    id: session.id,
    customerId: session.customerId,
    name: session.name,
    mobile: session.mobile,
  });
}
