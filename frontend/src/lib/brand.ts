/**
 * Brand / company constants used across the public marketing site.
 * Working values for the in-development build — finalise with legal/compliance.
 */
export const BRAND = {
  legalName: "NAVIX Technologies Private Limited",
  shortName: "NAVIX",
  tagline: "Lending Platform",
  phone: "+91 80 4718 2200",
  phoneHref: "tel:+918047182200",
  email: "support@navix.finance",
  grievanceEmail: "grievance@navix.finance",
  fraudEmail: "report@navix.finance",
  hours: "Mon–Sat, 9:30 AM – 6:30 PM",
  cin: "U65999KA2026PTC000000",
  address: {
    line1: "WeWork Prestige Atlanta, 80 Feet Road",
    line2: "Koramangala 1A Block, Bengaluru",
    city: "Bengaluru",
    pin: "560034",
  },
  maxLoanLakh: "5",
} as const;

/** RBI-registered NBFC lending partners (placeholder disclosures). */
export const LENDING_PARTNERS = [
  {
    name: "Arthveda Capital Private Limited",
    corNo: "N-14.03XXXX",
    blurb: "Lender of record · sanction letter & Key Fact Statement issued by the NBFC.",
  },
  {
    name: "Sentinel Finserv Limited",
    corNo: "N-13.02XXXX",
    blurb: "Lender of record · sanction letter & Key Fact Statement issued by the NBFC.",
  },
] as const;
