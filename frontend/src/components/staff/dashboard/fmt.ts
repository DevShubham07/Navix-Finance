/** Small display helpers shared by the dashboard widgets. Dates are IST calendar strings. */

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "2026-10-09" -> "09 Oct". Anything that is not a date string is returned unchanged. */
export function fmtDay(d: string | null | undefined): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(d ?? "");
  return m ? `${m[3]} ${MONTHS[Number(m[2]) - 1]}` : (d ?? "—");
}

/** "2026-10-09" -> "09 Oct 2026". */
export function fmtDayLong(d: string | null | undefined): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(d ?? "");
  return m ? `${m[3]} ${MONTHS[Number(m[2]) - 1]} ${m[1]}` : (d ?? "—");
}

/** "2026-10" -> "Oct 2026". */
export function fmtMonth(m: string | null | undefined): string {
  const x = /^(\d{4})-(\d{2})/.exec(m ?? "");
  return x ? `${MONTHS[Number(x[2]) - 1]} ${x[1]}` : (m ?? "—");
}

/** "2026-10" -> "Oct '26". */
export function fmtMonthShort(m: string | null | undefined): string {
  const x = /^(\d{4})-(\d{2})/.exec(m ?? "");
  return x ? `${MONTHS[Number(x[2]) - 1]} '${x[1].slice(2)}` : (m ?? "—");
}

/** Label for whatever a chart's x value is: a day, a month, or plain text. */
export function fmtAxisLabel(v: unknown): string {
  const s = String(v ?? "");
  if (/^\d{4}-\d{2}-\d{2}/.test(s)) return fmtDayLong(s);
  if (/^\d{4}-\d{2}$/.test(s)) return fmtMonth(s);
  return s;
}

export const nf = (n: number | null | undefined): string =>
  n == null ? "—" : n.toLocaleString("en-IN");

/** Today (or `d`) as yyyy-MM-dd in local time (the dashboard's period helpers are local-time too). */
export function todayIso(d: Date = new Date()): string {
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** "2026-10" -> { from: "2026-10-01", to: "2026-10-31" }. */
export function monthRange(m: string): { from: string; to: string } {
  const [y, mo] = m.split("-").map(Number);
  const last = new Date(y, mo, 0).getDate();
  return { from: `${m}-01`, to: `${m}-${String(last).padStart(2, "0")}` };
}
