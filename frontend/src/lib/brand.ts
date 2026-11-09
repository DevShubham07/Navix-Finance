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
    phone: "+91 92113 31168",
    phoneHref: "tel:+919211331168",
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

/**
 * Shown at the top of the four new policy pages until they are approved under DB-LGL-004.
 * On approval: set to "", remove `robots` from each page.tsx, and add the routes to sitemap.ts.
 */
export const POLICY_DRAFT_NOTICE =
  '<div class="callout"><b>Draft for review.</b> This page is awaiting approval by Legal, Compliance and MLB Securities Private Limited and is not yet in force.</div>';

/** The RBI-registered NBFC that sanctions and disburses every loan (the lender of record). */
export const NBFC_PARTNER = {
  name: "MLB Securities Private Limited",
  cin: "U74899DL1995PTC067535",
  disclaimer:
    "MLB Securities Private Limited (RBI Registration No. 14.00526) is a registered NBFC with the Reserve Bank of India. The company is having a valid Certificate of Registration dated 12.03.2025 issued by Reserve Bank of India under section 45 IA of the Reserve Bank of India Act, 1934. However, the Reserve Bank of India does not accept any responsibility or guarantee about the present position as to the financial soundness of the company or for the correctness of any of the statements or representations made or opinions expressed by the company and for the repayment of deposits/discharge of liabilities by the company. Loan approval is subject to credit assessment and verification. Interest rates and charges are subject to change. Please read all terms and conditions carefully before applying.",
} as const;
