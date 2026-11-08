/* eslint-disable */
import { BRAND, NBFC_PARTNER, POLICY_DRAFT_NOTICE } from "@/lib/brand";

export const html = `<section class="page active" id="corporate-governance">
  <div class="page-hero"><div class="wrap"><div class="crumb"><a href="/" data-link="">Home</a> &nbsp;/&nbsp; Corporate Governance</div><h1>Corporate Governance Policy</h1><p>How responsibilities are structured for loans offered through DhanBoost. Last updated: November 2026.</p></div></div>
  <div class="sec"><div class="wrap"><div class="prose">
    ${POLICY_DRAFT_NOTICE}
    <h2>1. Who does what</h2>
    <ul>
      <li><b>Lender:</b> ${NBFC_PARTNER.name} (CIN ${NBFC_PARTNER.cin}), an RBI-registered NBFC, sanctions and disburses every loan and remains responsible for it. RBI Certificate of Registration No. 14.00526, dated 12 March 2025.</li>
      <li><b>Platform:</b> ${BRAND.legalName} (CIN ${BRAND.cin}) operates DhanBoost and provides the technology and services for the loan journey. It is not a lender.</li>
    </ul>
    <h2>2. Our principles</h2>
    <ul>
      <li>Clear disclosure of who the lender is and what a loan costs.</li>
      <li>Decisions made under defined roles and recorded.</li>
      <li>Compliance with applicable law and Reserve Bank of India directions.</li>
      <li>Fair treatment of every customer.</li>
    </ul>
    <h2>3. Roles and oversight</h2>
    <p>Credit, disbursement, accounting and collections are separate functions. Settlement proposals must be approved by someone other than the person who proposed them. Actions on an application are recorded in an audit trail.</p>
    <h2>4. Policies and approval</h2>
    <p>Public policies and customer documents are reviewed by Legal, Compliance, Finance and the lender before publication, and each approved version is retained.</p>
    <h2>5. Complaints and accountability</h2>
    <p>Customers can raise concerns through <a href="/grievance" data-link="">Grievance Redressal</a>, where our Grievance Redressal Officer is named.</p>
    <h2>6. Review</h2>
    <p>This policy is reviewed periodically and whenever the lender arrangement or applicable law changes.</p>
  </div></div></div>
</section>`;
