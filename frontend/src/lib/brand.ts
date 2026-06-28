/**
 * Brand / company constants used across the public marketing site.
 * Working values for the in-development build — finalise with legal/compliance.
 */
export const BRAND = {
  legalName: "NAVIX Finance Private Limited",
  shortName: "NAVIX",
  tagline: "Lending Platform",
  phone: "+91 97167 60246",
  phoneHref: "tel:+9197167 60246",
  email: "info@navixfinance.com",
  grievanceEmail: "info@navixfinance.com",
  fraudEmail: "info@navixfinance.com",
  hours: "Mon–Sat, 9:30 AM – 6:30 PM",
  cin: "U64990HR2026PTC144926",
  address: {
    line1: "Dev Nagar",
    line2: "Gurugram",
    city: "Gurugram",
    pin: "122102",
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
