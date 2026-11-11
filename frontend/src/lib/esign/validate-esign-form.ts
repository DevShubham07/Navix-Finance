/** Pure validation for the admin "New eSign document" form. */
import { MAX_BOXES_PER_SIGNER, MIN_BOX_HEIGHT, MIN_BOX_WIDTH, type Box } from "./placement";

export const MAX_SIGNERS = 5;
export const MAX_PDF_BYTES = 10 * 1024 * 1024;

export interface SignerForm {
  name: string;
  email: string;
  self: boolean;
  /** Signature boxes in PDF points, top-left origin (see placement.ts). */
  boxes: Box[];
}

export interface EsignFormState {
  title: string;
  hasFile: boolean;
  sendEmail: boolean;
  signers: SignerForm[];
}

export function newSigner(_index = 0, self = false, name = ""): SignerForm {
  return { name, email: "", self, boxes: [] };
}

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** Human-readable problems; empty array = valid. */
export function validateEsignForm(f: EsignFormState): string[] {
  const errs: string[] = [];
  if (!f.hasFile) errs.push("Upload a PDF.");
  if (!f.title.trim()) errs.push("Enter a title.");
  if (f.signers.length === 0) errs.push("Add at least one signer.");
  if (f.signers.length > MAX_SIGNERS) errs.push(`At most ${MAX_SIGNERS} signers.`);
  f.signers.forEach((s, i) => {
    const who = `Signer ${i + 1}`;
    if (!s.name.trim()) errs.push(`${who}: enter the name exactly as on Aadhaar.`);
    if (s.boxes.length === 0) errs.push(`${who}: each signer needs at least one signature box.`);
    if (s.boxes.length > MAX_BOXES_PER_SIGNER) errs.push(`${who}: at most ${MAX_BOXES_PER_SIGNER} signature boxes.`);
    if (s.boxes.some((b) => b.width < MIN_BOX_WIDTH || b.height < MIN_BOX_HEIGHT)) {
      errs.push(`${who}: a signature box is smaller than ${MIN_BOX_WIDTH} x ${MIN_BOX_HEIGHT} points.`);
    }
    const email = s.email.trim();
    if (!s.self && f.sendEmail && !email) errs.push(`${who}: email is required to send the signing link.`);
    if (!s.self && email && !EMAIL_RE.test(email)) errs.push(`${who}: enter a valid email.`);
  });
  return errs;
}
