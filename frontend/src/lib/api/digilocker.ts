/**
 * Typed client for the DigiLocker 5-step KYC flow.
 *
 * IMPORTANT: On the frontend these methods MUST call the Next.js BFF
 * (server-side route handlers), which in turn call DigiLocker with the
 * X-Client-ID / X-Client-Secret headers (DIGILOCKER_CLIENT_ID /
 * DIGILOCKER_CLIENT_SECRET). NEVER call DigiLocker directly from the
 * browser — the secrets are server-side only.
 *
 * Flow: initialize -> (user consents) -> poll status until completed ->
 *       list documents -> fetch a document -> fetch Aadhaar XML.
 *
 * All method bodies are stubs (TODO) and currently proxy via the BFF.
 */

import { apiClient } from "@/lib/api/client";

export interface DigilockerInitializeRequest {
  /** Reference id for the onboarding application. */
  reference_id: string;
}

export interface DigilockerInitializeResponse {
  /** Opaque session/transaction id used for subsequent calls. */
  session_id: string;
  /** Hosted DigiLocker consent URL to redirect the user to. */
  authorization_url: string;
}

export type DigilockerStatusValue =
  | "PENDING"
  | "IN_PROGRESS"
  | "COMPLETED"
  | "FAILED";

export interface DigilockerStatusResponse {
  session_id: string;
  status: DigilockerStatusValue;
  completed: boolean;
}

export interface DigilockerDocumentSummary {
  doc_type: string;
  name: string;
  uri: string;
  issuer: string;
}

export interface DigilockerListDocumentsResponse {
  session_id: string;
  documents: DigilockerDocumentSummary[];
}

export interface DigilockerDocumentRequest {
  session_id: string;
  uri: string;
}

export interface DigilockerDocumentResponse {
  uri: string;
  mime_type: string;
  /** Base64-encoded document content. */
  content: string;
}

export interface DigilockerAadhaarXmlResponse {
  session_id: string;
  /** Raw Aadhaar XML payload. */
  xml: string;
  name: string;
  dob: string;
  gender: string;
  address: string;
  masked_aadhaar: string;
}

export const digilockerClient = {
  /** Step 1: initialize a DigiLocker session. TODO: proxy to BFF. */
  initialize(req: DigilockerInitializeRequest): Promise<DigilockerInitializeResponse> {
    // TODO: call BFF route that invokes digilocker_initialize
    return apiClient.post<DigilockerInitializeResponse>("/kyc/digilocker", {
      action: "initialize",
      ...req,
    });
  },

  /** Step 2: poll status until completed. TODO: proxy to BFF. */
  status(sessionId: string): Promise<DigilockerStatusResponse> {
    // TODO: call BFF route that invokes digilocker_status
    return apiClient.post<DigilockerStatusResponse>("/kyc/digilocker", {
      action: "status",
      session_id: sessionId,
    });
  },

  /** Step 3: list available documents. TODO: proxy to BFF. */
  listDocuments(sessionId: string): Promise<DigilockerListDocumentsResponse> {
    // TODO: call BFF route that invokes digilocker_list_documents
    return apiClient.post<DigilockerListDocumentsResponse>("/kyc/digilocker", {
      action: "list_documents",
      session_id: sessionId,
    });
  },

  /** Step 4: fetch a specific document. TODO: proxy to BFF. */
  document(req: DigilockerDocumentRequest): Promise<DigilockerDocumentResponse> {
    // TODO: call BFF route that invokes digilocker_document
    return apiClient.post<DigilockerDocumentResponse>("/kyc/digilocker", {
      action: "document",
      ...req,
    });
  },

  /** Step 5: fetch the Aadhaar XML. TODO: proxy to BFF. */
  aadhaarXml(sessionId: string): Promise<DigilockerAadhaarXmlResponse> {
    // TODO: call BFF route that invokes digilocker_aadhar_xml
    return apiClient.post<DigilockerAadhaarXmlResponse>("/kyc/digilocker", {
      action: "aadhaar_xml",
      session_id: sessionId,
    });
  },
};
