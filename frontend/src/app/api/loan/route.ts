import { NextResponse, type NextRequest } from "next/server";

/**
 * BFF loan route. Thin proxy to the Spring backend (BACKEND_BASE_URL) for
 * loan application listing/creation.
 */

export async function GET(_req: NextRequest) {
  // TODO: proxy to backend /loan and return the caller's loans/applications.
  return NextResponse.json({ loans: [], todo: "loan GET not implemented" });
}

export async function POST(_req: NextRequest) {
  // TODO: proxy a new LoanApplication to backend /loan.
  return NextResponse.json({ todo: "loan POST not implemented" }, { status: 501 });
}
