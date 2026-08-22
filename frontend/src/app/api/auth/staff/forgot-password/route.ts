import { NextResponse, type NextRequest } from "next/server";
import { config } from "@/lib/config";

/** Staff forgot-password. POST { email, mobile } -> backend; a generic ack (no enumeration). */
export async function POST(req: NextRequest) {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "Invalid JSON body." }, { status: 400 });
  }
  const { email, mobile, captchaToken } = (body ?? {}) as {
    email?: unknown;
    mobile?: unknown;
    captchaToken?: unknown;
  };
  let backendRes: Response;
  try {
    backendRes = await fetch(`${config.backendBaseUrl}/api/auth/staff/forgot-password`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({
        email: typeof email === "string" ? email : "",
        mobile: typeof mobile === "string" ? mobile : "",
        // Forwarded verbatim — only the backend may judge it.
        captchaToken: typeof captchaToken === "string" ? captchaToken : "",
      }),
      cache: "no-store",
    });
  } catch {
    return NextResponse.json({ error: "Could not reach the authentication service." }, { status: 502 });
  }
  const text = await backendRes.text();
  // Propagate the correlation id like the login routes do — the page now shows rejections rather
  // than swallowing them, so the error string needs a ref that greps to the backend logs.
  const headers: Record<string, string> = {
    "Content-Type": backendRes.headers.get("Content-Type") ?? "application/json",
  };
  const rid = backendRes.headers.get("X-Request-Id");
  if (rid) headers["X-Request-Id"] = rid;
  return new NextResponse(text, { status: backendRes.status, headers });
}
