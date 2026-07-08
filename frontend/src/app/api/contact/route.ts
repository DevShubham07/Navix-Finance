import { NextResponse, type NextRequest } from "next/server";
import { config } from "@/lib/config";

/**
 * Public marketing "Contact us" form. POST { name, email, phone, topic, message } -> backend, which
 * emails the enquiry to the NAVIX support inbox. No auth (a website visitor has no session); the body
 * is passed straight through and the backend's envelope is returned verbatim.
 */
export async function POST(req: NextRequest) {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "Invalid JSON body." }, { status: 400 });
  }
  const { name, email, phone, topic, message } = (body ?? {}) as {
    name?: unknown;
    email?: unknown;
    phone?: unknown;
    topic?: unknown;
    message?: unknown;
  };
  const str = (v: unknown) => (typeof v === "string" ? v : "");

  let backendRes: Response;
  try {
    backendRes = await fetch(`${config.backendBaseUrl}/api/contact`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({
        name: str(name),
        email: str(email),
        phone: str(phone),
        topic: str(topic),
        message: str(message),
      }),
      cache: "no-store",
    });
  } catch {
    return NextResponse.json({ error: "Could not reach the contact service." }, { status: 502 });
  }
  const text = await backendRes.text();
  return new NextResponse(text, {
    status: backendRes.status,
    headers: { "Content-Type": backendRes.headers.get("Content-Type") ?? "application/json" },
  });
}
