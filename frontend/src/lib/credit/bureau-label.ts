/**
 * The display name of the credit bureau a score actually came from.
 *
 * Scores were labelled "CIBIL" everywhere regardless of origin. CIBIL (TransUnion CIBIL), Experian
 * and CRIF Highmark are three separate licensed credit information companies, and we have never
 * pulled from CIBIL: scores came from Experian (via Signzy or Digitap) and now come from CRIF
 * Highmark (via Fintrix). Displaying an Experian or CRIF number under a competitor's brand is simply
 * wrong, it makes a reviewer cross-checking a real CIBIL report distrust the system when the numbers
 * differ — different bureaus score the same person differently — and it misattributes a CIC's data.
 *
 * `bureauSource` is the value stored on the profile (`SIGNZY_EXPERIAN`, `DIGITAP_EXPERIAN`,
 * `SIGNZY_CRIF`, `DIGITAP_CRIF`, `FINTRIX_CRIF`, `MANUAL`, …). When it is unknown or absent, fall back to the plain
 * "Bureau" — vague, but never false.
 */
export function bureauLabel(bureauSource?: string | null): string {
  const source = (bureauSource ?? "").toUpperCase();
  if (source.includes("CRIF")) return "CRIF";
  if (source.includes("EXPERIAN")) return "Experian";
  if (source.includes("CIBIL") || source.includes("TRANSUNION")) return "CIBIL";
  if (source.includes("EQUIFAX")) return "Equifax";
  return "Bureau";
}

/** `"CRIF score"` / `"Bureau score"` — the label for a score field. */
export function bureauScoreLabel(bureauSource?: string | null): string {
  return `${bureauLabel(bureauSource)} score`;
}
