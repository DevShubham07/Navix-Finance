/**
 * Brand / company constants used across the public marketing site.
 * Working values for the in-development build — finalise with legal/compliance.
 */
export const BRAND = {
  legalName: "NAVIX Finance Private Limited",
  shortName: "DhanBoost",
  tagline: "Lending Platform",
  phone: "+91 85100 28510",
  phoneHref: "tel:+918510028510",
  email: "support@dhanboost.com",
  grievanceEmail: "grievance@dhanboost.com",
  /** Grievance Redressal Officer under the Consumer Protection Act, 2019. */
  grievanceOfficer: {
    name: "Lalit Kumar",
    phone: "+91 97167 60246",
    phoneHref: "tel:+919716760246",
    email: "grievance@dhanboost.com",
  },
  fraudEmail: "support@dhanboost.com",
  hours: "Mon–Sat, 9:30 AM – 6:30 PM",
  cin: "U64990HR2026PTC144926",
  address: {
    line1: "Plot No 268, 1st Floor, Sector 33, Subhash Chowk, Islampur",
    line2: "Gurgaon, Haryana",
    city: "Gurgaon",
    pin: "122001",
  },
  maxLoanLakh: "10",
} as const;

/**
 * Anti-fraud notice shown as a rotating ticker on the support / policy pages and the marketing
 * footer (revamp.md decision 49).
 */
export const PAYMENT_SAFETY_NOTICE =
  "Always use our secure Repayment on Website Link for loan payments. Do not make direct bank payments to fake UPI links or unauthorised payment links. DhanBoost is not responsible for payments made to other accounts.";

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
