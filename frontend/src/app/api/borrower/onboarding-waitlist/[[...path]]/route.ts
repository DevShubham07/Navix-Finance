import { type NextRequest } from "next/server";
import { getBorrowerSession } from "@/lib/api/bff-session";
import { proxyToBackend, unauthorized } from "@/lib/api/bff-proxy";

/** Onboarding-paused waitlist (borrower): GET gate state, POST the form. */
async function handle(req: NextRequest) {
  const session = await getBorrowerSession();
  if (!session) return unauthorized("Borrower session required.");
  return proxyToBackend(req, "/api/onboarding-waitlist", session.token);
}

export const GET = handle;
export const POST = handle;
