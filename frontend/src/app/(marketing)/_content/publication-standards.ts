/* eslint-disable */
import { BRAND, NBFC_PARTNER, POLICY_DRAFT_NOTICE } from "@/lib/brand";
import { POLICY_CONTACTS } from "./policy-contacts";

/** DB-LGL-004, adapted: MLB Securities is the lender, NAVIX the technology platform. */
export const html = `<section class="page active" id="publication-standards">
  <div class="page-hero"><div class="wrap"><div class="crumb"><a href="/" data-link="">Home</a> &nbsp;/&nbsp; Publication Standards</div><h1>Website, Legal &amp; Compliance Publication Standards</h1><p>The controls we apply to our public website and customer-facing documents. Last updated: November 2026.</p></div></div>
  <div class="sec"><div class="wrap"><div class="prose">
    ${POLICY_DRAFT_NOTICE}
    <h2>1. Purpose</h2>
    <p>This standard sets minimum drafting, verification and approval controls for public-facing content published under DhanBoost at www.dhanboost.com. It is not a substitute for legal advice or lender-specific regulatory review.</p>
    <h2>2. Brand, lender and company information</h2>
    <ul>
      <li>Brand / website: <b>DhanBoost</b> – www.dhanboost.com</li>
      <li>Lender: <b>${NBFC_PARTNER.name}</b> (CIN ${NBFC_PARTNER.cin}, RBI Registration No. 14.00526)</li>
      <li>Technology platform: <b>${BRAND.legalName}</b> (CIN ${BRAND.cin}), ${BRAND.address.line1}, ${BRAND.address.line2} – ${BRAND.address.pin}, India</li>
    </ul>
    <p>Company names, CINs, registered offices, lending roles and registrations are checked against current authoritative records before publication. No brand, technology provider or service partner is presented as the lender unless that is legally accurate.</p>
    <h2>3. Contact directory</h2>
    <p>Every telephone number, email address and website link we publish is active, controlled by the relevant business and monitored by the responsible team.</p>
    ${POLICY_CONTACTS}
    <h2>4. Required website and legal content</h2>
    <ul>
      <li>Clear identification of the lender and each party's role.</li>
      <li>Loan product details, eligibility, tenure and repayment process.</li>
      <li>Access to the customer-specific KFS and loan agreement at the required stage.</li>
      <li>Accurate interest, fees, taxes, APR, net disbursal, total repayment and due dates.</li>
      <li>Privacy Policy, Terms &amp; Conditions, grievance process and other applicable disclosures.</li>
      <li>Clear customer support and grievance contact details.</li>
      <li>Secure application, consent, document access and payment flows.</li>
    </ul>
    <h2>5. Financial and product claims</h2>
    <ul>
      <li>Only lender-approved product terms and pricing are published.</li>
      <li>Illustrative figures are never presented as guaranteed offers, and APR is shown only once properly calculated.</li>
      <li>Website calculators and examples are labelled illustrative where appropriate and follow the approved calculation logic.</li>
      <li>No hidden fees, and no charges that are not lawfully applicable and properly disclosed.</li>
      <li>Any extension, rollover, instalment conversion, late charge, cancellation or prepayment term is approved, clearly disclosed and consistent across all documents.</li>
    </ul>
    <h2>6. Customer documents and data</h2>
    <ul>
      <li>Required disclosures, and an opportunity to review them, are given before loan acceptance.</li>
      <li>Electronic acceptance, e-signature and mandates use a valid authorised process.</li>
      <li>Browsing or starting an application is never treated as acceptance of a loan.</li>
      <li>Access to personal and financial information is restricted, in line with our <a href="/privacy" data-link="">Privacy Policy</a> and applicable law.</li>
      <li>Customer documents and records are accurate, retrievable and retained for the required period.</li>
    </ul>
    <h2>7. Review and approval workflow</h2>
    <ul>
      <li>The business owner confirms product and operational facts.</li>
      <li>Legal reviews terms, disclosures, company identity and applicable law.</li>
      <li>Compliance and the lender verify regulatory wording, pricing, APR, grievance details and customer-protection disclosures.</li>
      <li>Finance / tax checks fees, tax treatment, deductions and cash-flow calculations.</li>
      <li>The website owner tests links, forms, document access, contact channels and mobile display.</li>
      <li>A designated approver records sign-off, version number and effective date before publication.</li>
    </ul>
    <h2>8. Change control and monitoring</h2>
    <p>A controlled copy of every approved version is kept. Content is reviewed whenever product terms, lender arrangements, contact details, laws or website functionality change, and obsolete content is removed promptly. Each change records the reviewer, approval date, version and effective date.</p>
    <h2>9. Publication hold points</h2>
    <ul>
      <li>Nothing is published with unverified lender identity, corporate details or grievance contacts.</li>
      <li>No customer-specific sanction letter, KFS or agreement is issued with blank or inconsistent financial terms.</li>
      <li>No feature, customer portal, payment method or document download is described unless it works and has been tested.</li>
      <li>Documents are released only after Legal, Compliance and the lender have approved the final versions.</li>
    </ul>
    <h2>10. Legal note</h2>
    <p>This standard does not itself establish regulatory status or confirm that any product structure is approved. Applicable Indian law and binding regulatory requirements prevail. Document reference: DB-LGL-004.</p>
  </div></div></div>
</section>`;
