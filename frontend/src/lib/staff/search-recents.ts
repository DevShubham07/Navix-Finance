/**
 * Recent global-search queries, per staffer.
 *
 * <p>Deliberately its own module with no imports: the sign-out path clears these, and pulling the
 * whole nav tree (and lucide) into the auth bundle to reach one localStorage key would be silly.
 *
 * <p>These hold the typed QUERY STRING only — never a result row. A staffer searching a PAN puts
 * that PAN in their own browser, which is the same exposure as the input box; storing the matched
 * customer's name, id and status alongside it would not be.
 */

const RECENT_MAX = 8;

/** Per-staffer, so a shared console never shows one operator's queries to the next. */
export function recentKey(staffId: string): string {
  return `navix-staff-search-recent:${staffId}`;
}

export function readRecent(staffId: string): string[] {
  try {
    const raw = localStorage.getItem(recentKey(staffId));
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed)
      ? parsed.filter((v): v is string => typeof v === "string").slice(0, RECENT_MAX)
      : [];
  } catch {
    // Private mode / blocked storage / malformed JSON — recents are a convenience, never a
    // requirement, so every accessor here fails soft.
    return [];
  }
}

export function pushRecent(staffId: string, query: string): string[] {
  const q = query.trim();
  if (!q) return readRecent(staffId);
  const next = [q, ...readRecent(staffId).filter((v) => v !== q)].slice(0, RECENT_MAX);
  try {
    localStorage.setItem(recentKey(staffId), JSON.stringify(next));
  } catch {
    // ignore — see above
  }
  return next;
}

export function clearRecent(staffId: string): void {
  try {
    localStorage.removeItem(recentKey(staffId));
  } catch {
    // ignore — see above
  }
}
