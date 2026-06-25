import { NextResponse, type NextRequest } from "next/server";
import { setBorrowerSession } from "@/lib/api/bff-session";

/**
 * Borrower login (demo OTP). POST `{ mobile, applicantId?, name? }`.
 * Accepts the demo OTP "123456" -> sets the httpOnly `navix_borrower` cookie.
 * SEPARATE from staff auth — never shares a session/cookie.
 *
 * `applicantId` maps to the backend applicant; if omitted we derive a stable
 * numeric id from the mobile so the demo still threads an identity through.
 */
const DEMO_OTP = "123456";

function deriveApplicantId(mobile: string): number {
  const digits = mobile.replace(/\D/g, "");
  // Last 7 digits keep it within a sane positive integer range for the demo.
  const tail = digits.slice(-7);
  const n = Number.parseInt(tail || "1", 10);
  return Number.isFinite(n) && n > 0 ? n : 1;
}

export async function POST(req: NextRequest) {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "Invalid JSON body." }, { status: 400 });
  }

  const { mobile, otp, applicantId, name } = (body ?? {}) as {
    mobile?: unknown;
    otp?: unknown;
    applicantId?: unknown;
    name?: unknown;
  };

  if (typeof mobile !== "string" || mobile.replace(/\D/g, "").length < 10) {
    return NextResponse.json({ error: "Enter a valid 10-digit mobile number." }, { status: 400 });
  }

  // Demo OTP gate. If an OTP is supplied it must match; the dedicated login
  // page verifies the OTP client-side, but we re-check here when present.
  if (otp !== undefined && otp !== DEMO_OTP) {
    return NextResponse.json({ error: "Incorrect code. For this demo, use 123456." }, { status: 401 });
  }

  const resolvedApplicantId =
    typeof applicantId === "number" && Number.isFinite(applicantId) && applicantId > 0
      ? Math.trunc(applicantId)
      : deriveApplicantId(mobile);

  const session = {
    id: `borrower-${resolvedApplicantId}`,
    applicantId: resolvedApplicantId,
    name: typeof name === "string" && name.trim() ? name.trim() : "Aarav Sharma",
    mobile,
  };

  await setBorrowerSession(session);
  return NextResponse.json(session);
}
