import { BRAND } from "@/lib/brand";

const BASE = "https://www.navixfinance.com";

/**
 * Site-wide JSON-LD for the public marketing pages. Rendered once in the (marketing) layout so
 * it ships server-side on every marketing page.
 *
 * `@type` is `Organization`, deliberately NOT `FinancialService`: NAVIX is a lending PLATFORM,
 * not the lender of record (the RBI-registered NBFC partners are). `FinancialService` would
 * machine-assert that NAVIX provides the loan. Lending is represented separately (via
 * `LoanOrCredit` with `provider` = the partner NBFC) as a later, content-gated step.
 *
 * Facts trace to BRAND (frontend/src/lib/brand.ts) — the single source of truth. No
 * `SearchAction` (the site has no on-site search) and no `Review`/`AggregateRating` (the
 * `/reviews` figures are unverified — marking them up would be a policy violation).
 */
export function StructuredData() {
  const graph = {
    "@context": "https://schema.org",
    "@graph": [
      {
        "@type": "Organization",
        "@id": `${BASE}/#organization`,
        name: BRAND.shortName,
        legalName: BRAND.legalName,
        url: BASE,
        logo: `${BASE}/navix-mark.png`,
        image: `${BASE}/navix-mark.png`,
        telephone: BRAND.phone,
        email: BRAND.email,
        address: {
          "@type": "PostalAddress",
          streetAddress: BRAND.address.line1,
          addressLocality: BRAND.address.city,
          postalCode: BRAND.address.pin,
          addressCountry: "IN",
        },
        identifier: {
          "@type": "PropertyValue",
          propertyID: "CIN",
          value: BRAND.cin,
        },
        areaServed: { "@type": "Country", name: "IN" },
        // Populate with the real LinkedIn/company profile URLs when available (cheap
        // entity/knowledge-graph + E-E-A-T signal). Ships empty fine.
        sameAs: [] as string[],
      },
      {
        "@type": "WebSite",
        "@id": `${BASE}/#website`,
        name: BRAND.shortName,
        url: BASE,
        inLanguage: "en-IN",
        publisher: { "@id": `${BASE}/#organization` },
      },
    ],
  };

  return (
    <script
      type="application/ld+json"
      dangerouslySetInnerHTML={{ __html: JSON.stringify(graph) }}
    />
  );
}
