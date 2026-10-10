/**
 * Helpers that lift copy out of the design-export HTML strings in `(marketing)/_content/*.ts`, so
 * pages rebuilt in the landing layout keep their text (and legal wording) verbatim instead of
 * re-typing it. Pure string functions — they run on the server at render time.
 */

/** Hero copy from a `.page-hero` block: breadcrumb label, h1 and lead paragraph. */
export function parseHero(html: string): { crumb: string; title: string; lead: string } {
  const hero = html.match(/<div class="page-hero">([\s\S]*?)<\/div><\/div>/)?.[1] ?? "";
  const crumb = hero.match(/&nbsp;\/&nbsp;\s*([^<]*)<\/div>/)?.[1]?.trim() ?? "";
  const title = hero.match(/<h1>([\s\S]*?)<\/h1>/)?.[1]?.trim() ?? "";
  const lead = hero.match(/<\/h1>\s*<p>([\s\S]*?)<\/p>/)?.[1]?.trim() ?? "";
  return { crumb: decode(crumb), title: decode(title), lead: decode(lead) };
}

export type TocEntry = { id: string; label: string };

/**
 * The body of a `.prose` document with an `id` on every h2 (for the table of contents). The
 * markup inside is returned untouched apart from those ids.
 */
export function parseProse(html: string): { body: string; toc: TocEntry[] } {
  const start = html.search(/<div class="prose"[^>]*>/);
  if (start < 0) return { body: "", toc: [] };
  const open = html.slice(start).match(/<div class="prose"[^>]*>/)![0];
  let body = html.slice(start + open.length);
  // Drop the closers of .prose/.wrap/.sec and the section.
  body = body.replace(/\s*<\/div><\/div><\/div>\s*<\/section>\s*$/, "");
  return withHeadingIds(body);
}

/** Adds slug ids to plain `<h2>` tags and returns the list for a table of contents. */
export function withHeadingIds(body: string): { body: string; toc: TocEntry[] } {
  const toc: TocEntry[] = [];
  const seen = new Map<string, number>();
  const out = body.replace(/<h2>([\s\S]*?)<\/h2>/g, (_m, inner: string) => {
    const label = decode(inner.replace(/<[^>]+>/g, "")).trim();
    let id = slugify(label) || "section";
    const n = seen.get(id) ?? 0;
    seen.set(id, n + 1);
    if (n) id = `${id}-${n + 1}`;
    toc.push({ id, label });
    return `<h2 id="${id}">${inner}</h2>`;
  });
  return { body: out, toc };
}

export type FaqGroup = { title: string; items: { q: string; a: string }[] };

/** FAQ groups from the FAQ page markup (eyebrow-labelled `.faq` lists of `.qa` items). */
export function parseFaq(html: string): FaqGroup[] {
  const groups: FaqGroup[] = [];
  const parts = html.split(/<span class="eyebrow">/).slice(1);
  for (const part of parts) {
    const title = decode(part.slice(0, part.indexOf("</span>")).trim());
    const items: { q: string; a: string }[] = [];
    const re = /<button class="q"[^>]*>([\s\S]*?)<span class="qi">[\s\S]*?<div class="a">([\s\S]*?)<\/div><\/div>/g;
    let m: RegExpExecArray | null;
    while ((m = re.exec(part))) items.push({ q: decode(m[1].trim()), a: m[2].trim() });
    if (items.length) groups.push({ title, items });
  }
  return groups;
}

/** Strips the `.page-hero` block, leaving the rest of the page markup as-is. */
export function withoutHero(html: string): string {
  return html.replace(/<div class="page-hero">[\s\S]*?<\/div><\/div>/, "");
}

function slugify(s: string): string {
  return s
    .toLowerCase()
    .replace(/&/g, "and")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

/** Decodes the handful of entities the design export uses (text nodes only). */
export function decode(s: string): string {
  return s
    .replace(/<br\s*\/?>/g, " ")
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&quot;/g, '"')
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/\s+/g, " ");
}
