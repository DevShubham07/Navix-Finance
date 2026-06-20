import { NextResponse, type NextRequest } from "next/server";

/**
 * BFF PAN route. Thin proxy to the Spring backend (BACKEND_BASE_URL),
 * which calls Fintrix pan_comprehensive with HTTP Basic auth (secrets
 * stay server-side).
 */

export async function POST(_req: NextRequest) {
  // TODO: proxy { pan } to backend /kyc/pan and return PanComprehensiveResponse.
  return NextResponse.json({ todo: "pan POST not implemented" }, { status: 501 });
}
