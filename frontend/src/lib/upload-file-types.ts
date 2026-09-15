/**
 * What a borrower is allowed to upload, and why the check is not a plain MIME whitelist.
 *
 * Every document screen used to validate with `"application/pdf,image/jpeg,image/png".split(",")
 * .includes(file.type)`. That rejects three things real phones hand the browser every day:
 *  - `image/jpg` — what several Android gallery/picker apps report instead of `image/jpeg`
 *  - `image/heic` / `image/heif` — the iPhone camera's default format since iOS 11
 *  - `image/webp` — what a cheque photo saved out of WhatsApp often becomes
 * and anything a file manager hands over with an empty or `application/octet-stream` type, which
 * Android's Files app does routinely.
 *
 * A rejected file leaves the submit button disabled with no way forward, and on the disbursal-account
 * screen that upload IS the escape hatch from the penny-drop lock — so a borrower whose phone reports
 * an unfashionable MIME string was simply stuck. Hence: accept on EITHER a known type or a known
 * extension, so a missing/odd `file.type` falls back to the filename instead of blocking.
 *
 * The bytes are still judged by a human (and, for images, re-encoded by `compressImage`), so this is
 * a "did you pick a document" guard, not a security boundary.
 */

/** The `accept` attribute: what the picker offers. Deliberately wider than the old triplet. */
export const DOC_UPLOAD_ACCEPT =
  "application/pdf,image/jpeg,image/png,image/heic,image/heif,image/webp,.pdf,.jpg,.jpeg,.png,.heic,.heif,.webp";

export const DOC_UPLOAD_MAX_BYTES = 10 * 1024 * 1024;

const ALLOWED_TYPES = new Set([
  "application/pdf",
  "image/jpeg",
  "image/jpg",
  "image/png",
  "image/heic",
  "image/heif",
  "image/webp",
]);

const ALLOWED_EXTENSIONS = new Set(["pdf", "jpg", "jpeg", "png", "heic", "heif", "webp"]);

function extensionOf(fileName: string): string {
  const dot = fileName.lastIndexOf(".");
  return dot === -1 ? "" : fileName.slice(dot + 1).toLowerCase();
}

/**
 * Validate a picked document. Returns an error message to show, or `undefined` when the file is fine.
 * Accepts on type OR extension — see the note above on why a missing type must not block.
 */
export function checkDocumentFile(
  file: File,
  maxBytes: number = DOC_UPLOAD_MAX_BYTES,
): string | undefined {
  const typeOk = ALLOWED_TYPES.has(file.type.toLowerCase());
  const extOk = ALLOWED_EXTENSIONS.has(extensionOf(file.name));
  if (!typeOk && !extOk) return "Upload a PDF or a photo (JPG, PNG or HEIC).";
  if (file.size > maxBytes) return `File must be under ${Math.round(maxBytes / (1024 * 1024))} MB.`;
  return undefined;
}
