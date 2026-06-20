/**
 * Typed client for Fintrix verification endpoints.
 *
 * IMPORTANT: On the frontend these methods MUST call the Next.js BFF
 * (server-side route handlers), which in turn call Fintrix with the
 * HTTP Basic credentials (FINTRIX_CLIENT_ID / FINTRIX_CLIENT_SECRET).
 * NEVER call https://admin.fintrix.tech directly from the browser —
 * the secrets are server-side only.
 *
 * Base (server-side): FINTRIX_BASE_URL =
 *   https://admin.fintrix.tech/__api/api/v1/
 *
 * All method bodies are stubs (TODO) and currently proxy via the BFF.
 */

import { apiClient } from "@/lib/api/client";

/* ----------------------------- pan_comprehensive ---------------------------- */

export interface PanComprehensiveRequest {
  pan: string;
}

export interface PanComprehensiveResponse {
  full_name: string;
  dob: string;
  gender: string;
  aadhaar_linked: boolean;
  masked_aadhaar: string;
  address: string;
}

/* --------------------------- cv_email_verification -------------------------- */

export interface EmailVerificationRequest {
  email: string;
  /** Employer name to match against EPFO establishment records. */
  establishment_name?: string;
}

export interface EmailVerificationResponse {
  is_verified: boolean;
  /** Whether the email's employer matches the EPFO establishment. */
  is_establishment_matched: boolean;
}

/* -------------------------- ent_address_verification ------------------------ */

export interface AddressVerificationRequest {
  address: string;
  pincode: string;
}

export interface AddressVerificationResponse {
  address: string;
  pincode: string;
  state: string;
}

/* ----------------------------- individual_experian -------------------------- */

export interface CreditTradeline {
  lender: string;
  account_type: string;
  outstanding: number;
  status: string;
}

export interface CreditBureauRequest {
  pan: string;
  full_name: string;
  dob: string;
  mobile: string;
}

export interface CreditBureauResponse {
  credit_score: number;
  tradelines: CreditTradeline[];
  /** Bureau used to source this report. */
  bureau: "EXPERIAN" | "CRIF";
}

/* ----------------------------- verification_pennydrop ----------------------- */

export interface PennydropRequest {
  account_number: string;
  ifsc: string;
  /** Expected name to gate against the bank-returned account holder name. */
  expected_name: string;
}

export interface PennydropResponse {
  account_exists: boolean;
  full_name: string;
  /** True when the bank name matches the expected name (gate). */
  name_match: boolean;
}

export const fintrixClient = {
  /** PAN comprehensive lookup. TODO: proxy to BFF /api/kyc/pan. */
  panComprehensive(req: PanComprehensiveRequest): Promise<PanComprehensiveResponse> {
    // TODO: call BFF route that invokes Fintrix pan_comprehensive
    return apiClient.post<PanComprehensiveResponse>("/kyc/pan", req, {
      baseUrl: undefined,
    });
  },

  /** Email + EPFO establishment verification. TODO: proxy to BFF. */
  emailVerification(req: EmailVerificationRequest): Promise<EmailVerificationResponse> {
    // TODO: call BFF route that invokes Fintrix cv_email_verification
    return apiClient.post<EmailVerificationResponse>("/verification/email", req);
  },

  /** Address + pincode verification. TODO: proxy to BFF. */
  addressVerification(req: AddressVerificationRequest): Promise<AddressVerificationResponse> {
    // TODO: call BFF route that invokes Fintrix ent_address_verification
    return apiClient.post<AddressVerificationResponse>("/verification/address", req);
  },

  /** PRIMARY credit pull via Experian. TODO: proxy to BFF. */
  individualExperian(req: CreditBureauRequest): Promise<CreditBureauResponse> {
    // TODO: call BFF route that invokes Fintrix individual_experian
    return apiClient.post<CreditBureauResponse>("/verification/experian", req);
  },

  /** FALLBACK credit pull via CRIF. TODO: proxy to BFF. */
  individualCrif(req: CreditBureauRequest): Promise<CreditBureauResponse> {
    // TODO: call BFF route that invokes Fintrix individual_crif
    return apiClient.post<CreditBureauResponse>("/verification/crif", req);
  },

  /** Penny-drop bank account verification + name-match gate. TODO: proxy to BFF. */
  pennydrop(req: PennydropRequest): Promise<PennydropResponse> {
    // TODO: call BFF route that invokes Fintrix verification_pennydrop
    return apiClient.post<PennydropResponse>("/verification/pennydrop", req);
  },
};
