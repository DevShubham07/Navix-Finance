/**
 * Short staff-facing labels + ordering for the stored `journeyStep` pointer
 * (`JourneyService.Step` while DRAFT, `OfferStep` while SANCTIONED). Pure; mirrors the backend enums.
 * Index/total are against the full list — a re-apply's journey is shorter, so treat as approximate.
 */
const INTAKE: Array<[string, string]> = [
  ["START", "Start"],
  ["OTP", "Mobile OTP"],
  ["SET_PASSWORD", "Password"],
  ["EMPLOYMENT", "Employment"],
  ["EMPLOYER", "Employer"],
  ["EMAIL", "Email"],
  ["BANK", "Salary account"],
  ["PAYSLIPS", "Payslips"],
  ["AADHAAR", "Aadhaar"],
  ["PAN_CARD", "PAN card"],
  ["CONSENT", "Consent"],
  ["SUBMITTED", "Submitted"],
];

const OFFER: Array<[string, string]> = [
  ["OFFER_AMOUNT", "Amount"],
  ["OFFER_REPAYMENT_DATE", "Repayment date"],
  ["OFFER_DIGILOCKER", "DigiLocker"],
  ["OFFER_REFERENCES", "References"],
  ["OFFER_SUMMARY", "Summary"],
  ["OFFER_SELFIE", "Selfie"],
  ["OFFER_ADDRESS", "Address"],
  ["OFFER_SANCTION_LETTER", "Sanction letter"],
  ["OFFER_ESIGN", "eSign"],
  ["OFFER_SANCTIONED", "Sanctioned"],
  ["OFFER_DISBURSAL_ACCOUNT", "Disbursal account"],
  ["OFFER_MANDATE", "Mandate"],
];

export interface StepInfo {
  journey: "intake" | "offer";
  label: string;
  /** 1-based position. */
  n: number;
  total: number;
}

export function stepInfo(step?: string | null): StepInfo | null {
  if (!step) return null;
  for (const [journey, list] of [["intake", INTAKE], ["offer", OFFER]] as const) {
    const i = list.findIndex(([k]) => k === step);
    if (i >= 0) return { journey, label: list[i][1], n: i + 1, total: list.length };
  }
  return null;
}
