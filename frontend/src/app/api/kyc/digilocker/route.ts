import { NextResponse, type NextRequest } from "next/server";

/**
 * BFF DigiLocker route. Thin proxy to the Spring backend (BACKEND_BASE_URL),
 * which calls DigiLocker with X-Client-ID / X-Client-Secret headers
 * (secrets stay server-side). Drives the 5-step flow via an `action` field:
 * initialize | status | list_documents | document | aadhaar_xml.
 */

export async function POST(_req: NextRequest) {
  // TODO: read { action, ... } and proxy to backend /kyc/digilocker/<action>.
  return NextResponse.json(
    { todo: "digilocker POST not implemented" },
    { status: 501 },
  );
}
