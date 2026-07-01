import { NextResponse, type NextRequest } from "next/server";
import { config } from "@/lib/config";

/** Borrower forgot-password. POST { email, mobile } -> backend; a generic ack (no enumeration). */
export async function POST(req: NextRequest) {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "Invalid JSON body." }, { status: 400 });
  }
  const { email, mobile } = (body ?? {}) as { email?: unknown; mobile?: unknown };
  let backendRes: Response;
  try {
    backendRes = await fetch(`${config.backendBaseUrl}/api/auth/borrower/forgot-password`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({
        email: typeof email === "string" ? email : "",
        mobile: typeof mobile === "string" ? mobile : "",
      }),
      cache: "no-store",
    });
  } catch {
    return NextResponse.json({ error: "Could not reach the authentication service." }, { status: 502 });
  }
  const text = await backendRes.text();
  return new NextResponse(text, {
    status: backendRes.status,
    headers: { "Content-Type": backendRes.headers.get("Content-Type") ?? "application/json" },
  });
}
