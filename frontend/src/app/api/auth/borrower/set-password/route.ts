import { NextResponse } from "next/server";
import { getBorrowerSession } from "@/lib/api/bff-session";
import { config } from "@/lib/config";

/** Set a password for the signed-in borrower. POST { password } -> backend (forwards the bearer). */
export async function POST(req: Request) {
  const session = await getBorrowerSession();
  if (!session) return NextResponse.json({ error: "Not signed in." }, { status: 401 });
  const body = (await req.json().catch(() => ({}))) as { password?: unknown };
  let backendRes: Response;
  try {
    backendRes = await fetch(`${config.backendBaseUrl}/api/auth/borrower/set-password`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
        Authorization: `Bearer ${session.token}`,
      },
      body: JSON.stringify({ password: typeof body.password === "string" ? body.password : "" }),
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
