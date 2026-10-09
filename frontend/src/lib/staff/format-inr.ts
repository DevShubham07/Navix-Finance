/**
 * Compact rupee formatting for chart axes and labels, in Indian units (lakh = 1,00,000; crore =
 * 1,00,00,000). Input is integer PAISE. Exact figures belong to `paiseToINR`; this is for places
 * where space is short: ₹13.3 L, ₹3.9 Cr. Below one lakh the full grouped figure is shown.
 */
const LAKH = 100_000;
const CRORE = 10_000_000;

const oneDp = (n: number): string => {
  const s = (Math.round(n * 10) / 10).toFixed(1);
  return s.endsWith(".0") ? s.slice(0, -2) : s;
};

export function formatInrCompact(paise: number | null | undefined): string {
  if (paise == null || !Number.isFinite(paise)) return "—";
  const rupees = Math.abs(paise) / 100;
  const rounded = Math.round(rupees);
  let body: string;
  if (rupees >= CRORE) {
    body = `${oneDp(rupees / CRORE)} Cr`;
  } else if (rounded >= LAKH) {
    // 99.96 L rounds to "100 L" — promote to crore rather than print a lakh figure >= 100.
    body = Math.round((rupees / LAKH) * 10) / 10 >= 100 ? `${oneDp(rupees / CRORE)} Cr` : `${oneDp(rupees / LAKH)} L`;
  } else {
    body = rounded.toLocaleString("en-IN");
  }
  return `${paise < 0 && rounded > 0 ? "-" : ""}₹${body}`;
}
