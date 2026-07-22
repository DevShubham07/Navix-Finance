/**
 * Brand / company constants used across the public marketing site.
 * Working values for the in-development build — finalise with legal/compliance.
 */
export const BRAND = {
  legalName: "NAVIX Finance Private Limited",
  shortName: "DhanBoost",
  tagline: "Lending Platform",
  phone: "+91 97167 60246",
  phoneHref: "tel:+9197167 60246",
  email: "info@dhanboost.com",
  grievanceEmail: "info@dhanboost.com",
  fraudEmail: "info@dhanboost.com",
  hours: "Mon–Sat, 9:30 AM – 6:30 PM",
  cin: "U64990HR2026PTC144926",
  address: {
    line1: "Dev Nagar",
    line2: "Gurugram",
    city: "Gurugram",
    pin: "122102",
  },
  maxLoanLakh: "10",
} as const;

/** RBI-registered NBFC lending partners (placeholder disclosures). */
export const LENDING_PARTNERS = [
  {
    name: "Arthveda Capital Private Limited",
    corNo: "", // TODO: real RBI CoR number (placeholder removed — see seoPlan.md Track B / B2)
    blurb: "Lender of record · sanction letter & Key Fact Statement issued by the NBFC.",
  },
  {
    name: "Sentinel Finserv Limited",
    corNo: "", // TODO: real RBI CoR number (placeholder removed — see seoPlan.md Track B / B2)
    blurb: "Lender of record · sanction letter & Key Fact Statement issued by the NBFC.",
  },
] as const;
