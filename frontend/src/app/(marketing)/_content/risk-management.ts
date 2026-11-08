/* eslint-disable */
import { NBFC_PARTNER, POLICY_DRAFT_NOTICE } from "@/lib/brand";

export const html = `<section class="page active" id="risk-management">
  <div class="page-hero"><div class="wrap"><div class="crumb"><a href="/" data-link="">Home</a> &nbsp;/&nbsp; Risk Management</div><h1>Risk Management Policy</h1><p>How risks are identified and managed on loans offered through DhanBoost. Last updated: November 2026.</p></div></div>
  <div class="sec"><div class="wrap"><div class="prose">
    ${POLICY_DRAFT_NOTICE}
    <h2>1. Purpose</h2>
    <p>This policy summarises how risks in the lending process are managed. ${NBFC_PARTNER.name}, as the lender, makes the final decision on credit risk and remains responsible for the loans it makes.</p>
    <h2>2. Credit risk</h2>
    <ul>
      <li>Loans are limited to 25% of monthly salary.</li>
      <li>Income, employment and bank details are verified before a loan is offered.</li>
      <li>A credit report is reviewed, with your consent, as part of the assessment.</li>
      <li>Each loan is a single repayment linked to your salary date, within 40 days of disbursal.</li>
    </ul>
    <h2>3. Fraud risk</h2>
    <p>Identity details are checked against official records, and mismatches are reviewed or rejected. See our <a href="/kyc-aml-policy" data-link="">KYC &amp; AML Policy</a>.</p>
    <h2>4. Operational risk</h2>
    <p>Credit decisions, release of funds and verification of repayments are handled by separate functions. Each stage of a loan is recorded in an audit trail showing who acted and when.</p>
    <h2>5. Data and technology risk</h2>
    <p>Access to customer information is limited by role, and data is encrypted in transit. See our <a href="/privacy" data-link="">Privacy Policy</a>.</p>
    <h2>6. Service provider risk</h2>
    <p>Verification, payment and communication providers are engaged for defined purposes and bound by confidentiality.</p>
    <h2>7. Recovery conduct risk</h2>
    <p>Recovery must follow our <a href="/fair-practices" data-link="">Fair Practices Code</a>. Concerns can be raised through <a href="/grievance" data-link="">Grievance Redressal</a>.</p>
    <h2>8. Review</h2>
    <p>This policy is reviewed periodically and whenever products, regulations or the lender arrangement change.</p>
  </div></div></div>
</section>`;
