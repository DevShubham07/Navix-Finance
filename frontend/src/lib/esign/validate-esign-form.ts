/** Pure validation + payload building for the admin "New eSign document" form. */

export const MAX_SIGNERS = 5;
export const MAX_PDF_BYTES = 10 * 1024 * 1024;

export const POSITIONS = [
  { value: "TopLeft", label: "Top left" },
  { value: "TopCenter", label: "Top center" },
  { value: "TopRight", label: "Top right" },
  { value: "MiddleLeft", label: "Middle left" },
  { value: "MiddleCenter", label: "Middle center" },
  { value: "MiddleRight", label: "Middle right" },
  { value: "BottomLeft", label: "Bottom left" },
  { value: "BottomCenter", label: "Bottom center" },
  { value: "BottomRight", label: "Bottom right" },
] as const;

/** Staggered defaults so signature boxes of successive signers don't overlap. */
export const DEFAULT_POSITIONS = ["BottomLeft", "BottomRight", "BottomCenter", "TopLeft", "TopRight"];

export type PagesMode = "All" | "First" | "Last" | "Custom";

export interface SignerForm {
  name: string;
  email: string;
  self: boolean;
  pagesMode: PagesMode;
  /** Comma list of page numbers, used when `pagesMode` is "Custom". */
  pagesCustom: string;
  position: string;
}

export interface EsignFormState {
  title: string;
  hasFile: boolean;
  sendEmail: boolean;
  signers: SignerForm[];
}

export function newSigner(index: number, self = false, name = ""): SignerForm {
  return {
    name,
    email: "",
    self,
    pagesMode: "All",
    pagesCustom: "",
    position: DEFAULT_POSITIONS[index % DEFAULT_POSITIONS.length],
  };
}

/** "1", "1,3", "2, 5" — positive integers, no duplicates. */
export function isValidPageList(s: string): boolean {
  const parts = s.split(",").map((p) => p.trim());
  if (parts.some((p) => !/^[1-9]\d{0,3}$/.test(p))) return false;
  return new Set(parts).size === parts.length;
}

export function pagesValue(s: SignerForm): string {
  return s.pagesMode === "Custom" ? s.pagesCustom.split(",").map((p) => p.trim()).join(",") : s.pagesMode;
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
    if (s.pagesMode === "Custom" && !isValidPageList(s.pagesCustom)) {
      errs.push(`${who}: pages must be numbers separated by commas, e.g. 1,3.`);
    }
    const email = s.email.trim();
    if (!s.self && f.sendEmail && !email) errs.push(`${who}: email is required to send the signing link.`);
    if (!s.self && email && !EMAIL_RE.test(email)) errs.push(`${who}: enter a valid email.`);
  });
  return errs;
}
