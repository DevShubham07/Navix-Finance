import { NextResponse, type NextRequest } from "next/server";

/**
 * BFF webhook receiver for asynchronous Fintrix callbacks. Validates the
 * payload and forwards it to the Spring backend (BACKEND_BASE_URL) for
 * processing (e.g. async verification / credit results).
 */

export async function POST(_req: NextRequest) {
  // TODO: verify signature, then forward to backend /webhooks/fintrix.
  return NextResponse.json({ received: true, todo: "fintrix webhook not implemented" });
}
