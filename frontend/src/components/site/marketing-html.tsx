/**
 * Renders a ported design-page section (raw HTML from `(marketing)/_content/*`).
 * The markup is our own build-time artifact (generated from the design export),
 * not user input, so dangerouslySetInnerHTML is safe here. Rendered on the
 * server, so the full content ships in the initial HTML (SEO-friendly).
 */
export function MarketingHtml({ html }: { html: string }) {
  return <div dangerouslySetInnerHTML={{ __html: html }} />;
}
