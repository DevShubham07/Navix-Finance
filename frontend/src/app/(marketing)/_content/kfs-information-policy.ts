/* eslint-disable */
import { BRAND, NBFC_PARTNER, POLICY_DRAFT_NOTICE } from "@/lib/brand";
import { POLICY_CONTACTS } from "./policy-contacts";

/** DB-LGL-002, adapted: MLB Securities is the lender, NAVIX the technology platform. */
export const html = `<section class="page active" id="kfs-information-policy">
  <div class="page-hero"><div class="wrap"><div class="crumb"><a href="/" data-link="">Home</a> &nbsp;/&nbsp; KFS Information Policy</div><h1>Key Facts Statement (KFS) Information Policy</h1><p>Customer disclosures on loan terms, charges, APR and repayment. Last updated: November 2026.</p></div></div>
  <div class="sec"><div class="wrap"><div class="prose">
    ${POLICY_DRAFT_NOTICE}
    <h2>1. Purpose</h2>
    <p>DhanBoost aims to present loan costs and repayment obligations clearly. The Key Facts Statement (KFS) for an individual offer is provided by ${NBFC_PARTNER.name}, the lender, in the form and at the stage required by applicable law. This policy is general information and is not an individual loan offer.</p>
    <h2>2. Lender and company details</h2>
    <ul>
      <li>Customer-facing brand: <b>DhanBoost</b></li>
      <li>Lender: <b>${NBFC_PARTNER.name}</b> (CIN ${NBFC_PARTNER.cin}, RBI Registration No. 14.00526)</li>
      <li>Technology platform: <b>${BRAND.legalName}</b> (CIN ${BRAND.cin}), ${BRAND.address.line1}, ${BRAND.address.line2} – ${BRAND.address.pin}, India</li>
    </ul>
    <h2>3. Information in the KFS</h2>
    <ul>
      <li>Borrower, loan reference and the lender's legal details.</li>
      <li>Approved principal, tenure, interest rate and calculation method.</li>
      <li>Processing fee, taxes and other permitted charges, itemised as applicable.</li>
      <li>Net disbursal, total repayment and the Annual Percentage Rate (APR).</li>
      <li>Repayment schedule, instalments and due dates.</li>
      <li>Applicable penal charges and exit terms.</li>
      <li>Authorised payment method, customer support and grievance details.</li>
    </ul>
    <h2>4. Timing, review and acceptance</h2>
    <p>You receive the KFS before you enter the loan contract and have a reasonable opportunity to review it. Website descriptions, advertisements and calculator outputs do not replace your customer-specific KFS. Viewing a page or starting an application is not loan acceptance.</p>
    <h2>5. Pricing, fees and APR</h2>
    <p>All pricing shown to you is approved by the lender and reflected in your KFS and agreement. The APR is calculated from the actual cash flows of your loan. Taxes and charges are itemised and disclosed before you accept.</p>
    <h2>6. Repayment and changes</h2>
    <ul>
      <li>Your KFS and repayment schedule state the amount, method and due date(s) payable.</li>
      <li>Any extension, rollover, restructuring or instalment conversion must be permitted by the agreement and applicable law.</li>
      <li>Any revised costs, due dates and obligations are disclosed and accepted where required; extensions are never automatic.</li>
      <li>Penal charges and early-repayment terms match your approved contract.</li>
    </ul>
    <h2>7. Customer review checklist</h2>
    <ul>
      <li>Confirm the legal name of the lender.</li>
      <li>Check principal, net disbursal and total repayment.</li>
      <li>Review interest, fees, taxes and APR.</li>
      <li>Confirm tenure, repayment dates and instalments.</li>
      <li>Read the applicable penal charges and exit terms.</li>
      <li>Save the KFS, agreement, repayment schedule and payment receipts.</li>
    </ul>
    <h2>8. Questions or errors</h2>
    <p>If your KFS appears incomplete or differs from the offer you accepted, contact customer support. For matters relating to the loan contract or regulated lending responsibilities, you may also use the lender's grievance mechanism described on our <a href="/grievance" data-link="">Grievance Redressal</a> page.</p>
    <h2>9. Contact details</h2>
    ${POLICY_CONTACTS}
    <h2>10. Scope</h2>
    <p>This policy does not replace your individual KFS or loan agreement. Document reference: DB-LGL-002.</p>
  </div></div></div>
</section>`;
