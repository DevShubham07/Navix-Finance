/** Mirrors the backend `PasswordPolicy` (revamp.md decision 23) — one rule for borrowers and staff. */
export const PASSWORD_MIN = 6;
export const PASSWORD_MAX = 10;
export const PASSWORD_HINT = `${PASSWORD_MIN}–${PASSWORD_MAX} characters with a letter, a digit and a special character`;

export function passwordOk(pw: string): boolean {
  return (
    pw.length >= PASSWORD_MIN &&
    pw.length <= PASSWORD_MAX &&
    /[A-Za-z]/.test(pw) &&
    /[0-9]/.test(pw) &&
    /[^A-Za-z0-9\s]/.test(pw)
  );
}
