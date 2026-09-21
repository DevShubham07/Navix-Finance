import { NAV, navVisible, type NavItem } from "@/components/staff/staff-nav";
import type { StaffRole } from "@/lib/auth/rbac";
import type { FeatureFlags } from "@/lib/api/applications";

/**
 * Pure logic behind the staff global-search palette — no React, no fetching, so it unit-tests in
 * Vitest's node environment.
 *
 * <p>The "Pages" half of the palette is resolved entirely here, from the same {@link NAV} array and
 * the same {@link navVisible} gate the sidebar renders. That is deliberate: a second, hand-kept list
 * of searchable pages would eventually drift, and a drifted list is an RBAC leak — it would offer a
 * staffer a page their role cannot open. Entity results (customers, applications, …) come from the
 * server, which scopes them with the same services the list pages use.
 */

/** A page/feature the caller may open, flattened out of the sidebar. */
export type FeatureHit = {
  kind: "feature";
  id: string;
  title: string;
  /** The sidebar group it came from ("Operations", "Administration", …). */
  subtitle: string;
  href: string;
  Icon: NavItem["Icon"];
  /** Lower-cased tokens this entry matches on. */
  keywords: string[];
};

/** How a raw query string was understood. Drives the hint chip and, server-side, which groups run. */
export type QueryKind = "mobile" | "pan" | "id" | "text";

const MOBILE_RE = /^(?:\+?91)?([6-9]\d{9})$/;
const PAN_RE = /^[A-Za-z]{5}\d{4}[A-Za-z]$/;

/** Strip a leading `#`, collapse whitespace. Mirrors `SearchQuery.parse` on the backend. */
function clean(raw: string): string {
  return raw.trim().replace(/^#/, "").replace(/\s+/g, " ").trim();
}

/**
 * Classify the query the way the backend will, so the palette can tell the user what it is doing
 * ("Searching by PAN") before the response lands.
 *
 * <p>Digits are ambiguous by nature: a 10-digit string starting 6-9 is an Indian mobile, anything
 * shorter is an id. Never both — an id lookup on a mobile-shaped number returns nothing useful and
 * costs a query.
 */
export function interpretQuery(raw: string): { kind: QueryKind; value: string } {
  const q = clean(raw);
  const digits = q.replace(/[^\d]/g, "");
  if (MOBILE_RE.test(q) || MOBILE_RE.test(digits)) {
    const m = MOBILE_RE.exec(q) ?? MOBILE_RE.exec(digits);
    return { kind: "mobile", value: m ? m[1] : digits };
  }
  if (PAN_RE.test(q)) return { kind: "pan", value: q.toUpperCase() };
  if (/^\d+$/.test(q)) return { kind: "id", value: q };
  return { kind: "text", value: q };
}

export const QUERY_KIND_LABEL: Record<QueryKind, string> = {
  mobile: "Searching by mobile",
  pan: "Searching by PAN",
  id: "Searching by ID",
  text: "Searching by name",
};

/**
 * Is this worth a network round-trip? Two characters for text (one-letter names match everything),
 * but a single digit is already a usable id prefix.
 */
export function isSearchable(raw: string): boolean {
  const q = clean(raw);
  if (!q) return false;
  return /^\d/.test(q) ? q.length >= 1 : q.length >= 2;
}

/** Tokens a nav entry should match on: its label, its group heading, and its URL path segments. */
function keywordsFor(item: NavItem, heading: string): string[] {
  const fromHref = item.href
    .split("?")[0]
    .split("/")
    .filter((s) => s && s !== "staff")
    .flatMap((s) => s.split("-"));
  return [
    ...item.label.toLowerCase().split(/\s+/),
    ...heading.toLowerCase().split(/\s+/),
    ...fromHref.map((s) => s.toLowerCase()),
  ];
}

/**
 * Every page this role can reach, including segment children (so "overdue" finds Loans · Overdue).
 * Filtered through {@link navVisible} — the sidebar's own gate — so it can never offer more than the
 * sidebar does.
 */
export function buildFeatureIndex(role: StaffRole, flags?: FeatureFlags): FeatureHit[] {
  const hits: FeatureHit[] = [];
  for (const group of NAV) {
    for (const item of group.items) {
      if (!navVisible(item, role, flags)) continue;
      hits.push({
        kind: "feature",
        id: item.href,
        title: item.label,
        subtitle: group.heading,
        href: item.href,
        Icon: item.Icon,
        keywords: keywordsFor(item, group.heading),
      });
      for (const child of item.sub ?? []) {
        const href = `${item.href.split("?")[0]}?seg=${child.seg}`;
        hits.push({
          kind: "feature",
          id: href,
          title: `${item.label} · ${child.label}`,
          subtitle: group.heading,
          href,
          Icon: item.Icon,
          keywords: [...keywordsFor(item, group.heading), ...child.label.toLowerCase().split(/\s+/)],
        });
      }
    }
  }
  return hits;
}

/** Token-prefix matches first (typing "cust" should put Customers above "Unallocated customers"). */
export function matchFeatures(index: FeatureHit[], raw: string, max = 5): FeatureHit[] {
  const q = clean(raw).toLowerCase();
  if (!q) return [];
  const prefix: FeatureHit[] = [];
  const substring: FeatureHit[] = [];
  for (const hit of index) {
    if (hit.keywords.some((k) => k.startsWith(q))) prefix.push(hit);
    else if (hit.title.toLowerCase().includes(q)) substring.push(hit);
  }
  return [...prefix, ...substring].slice(0, max);
}

// Recents live in their own import-free module so the sign-out path can clear them without
// pulling the nav tree into the auth bundle.
export { clearRecent, pushRecent, readRecent, recentKey } from "@/lib/staff/search-recents";
