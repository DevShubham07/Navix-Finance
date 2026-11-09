/** Mirrors the backend `PasswordPolicy` (revamp.md decision 23) — one rule for borrowers and staff. */
export const PASSWORD_MIN = 8;
/** BCrypt's input limit, in UTF-8 bytes (not characters). */
export const PASSWORD_MAX_BYTES = 72;
export const PASSWORD_HINT = `at least ${PASSWORD_MIN} characters (up to ${PASSWORD_MAX_BYTES}) with a letter, a digit and a special character`;

export function passwordOk(pw: string): boolean {
  return (
    pw.length >= PASSWORD_MIN &&
    new TextEncoder().encode(pw).length <= PASSWORD_MAX_BYTES &&
    /[A-Za-z]/.test(pw) &&
    /[0-9]/.test(pw) &&
    /[^A-Za-z0-9\s]/.test(pw)
  );
}
