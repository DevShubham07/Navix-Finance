import { type NextRequest, NextResponse } from "next/server";
import { config } from "@/lib/config";

/**
 * PayU browser return (surl/furl/curl). PayU POSTs here cross-site, so the sameSite=lax session
 * cookie is NOT sent: no session is read. The raw urlencoded body goes to the backend, which
 * verifies PayU's reverse hash and answers `{txnid}` only when valid. We then 303 to /repay with
 * just the txnid; no payment field or hash ever reaches the URL. Any failure still lands on /repay.
 */
export async function POST(req: NextRequest) {
  let txnid: string | null = null;
  try {
    const res = await fetch(`${config.backendBaseUrl}/api/webhooks/payu/return`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
      body: await req.text(),
      cache: "no-store",
    });
    const json = (await res.json()) as { data?: { txnid?: unknown } | null };
    const t = json?.data?.txnid;
    if (typeof t === "string" && /^[A-Za-z0-9]{1,25}$/.test(t)) txnid = t;
  } catch {
    /* backend down or unparsable: fall through to plain /repay */
  }
  return new NextResponse(null, {
    status: 303,
    headers: { Location: txnid ? `/repay?order=${txnid}` : "/repay" },
  });
}
