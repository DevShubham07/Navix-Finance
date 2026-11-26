/* eslint-disable */
import { BRAND, NBFC_PARTNER, POLICY_DRAFT_NOTICE } from "@/lib/brand";
import { POLICY_CONTACTS } from "./policy-contacts";

/** DB-LGL-003, adapted: MLB Securities is the lender, NAVIX the technology platform. */
export const html = `<section class="page active" id="loan-agreement-access-policy">
  <div class="page-hero"><div class="wrap"><div class="crumb"><a href="/" data-link="">Home</a> &nbsp;/&nbsp; Loan Agreement Access Policy</div><h1>Loan Agreement Access Policy</h1><p>How you can access, review, accept and keep your loan documents. Last updated: November 2026.</p></div></div>
  <div class="sec"><div class="wrap"><div class="prose">
    ${POLICY_DRAFT_NOTICE}
    <h2>1. Purpose and scope</h2>
    <p>This policy explains how customers may access, review, accept and retain loan documents for loans sanctioned by ${NBFC_PARTNER.name} and offered through DhanBoost, a platform operated by ${BRAND.legalName}.</p>
    <h2>2. Lender identification</h2>
    <p>The lender's legal name and required details appear in your offer, Key Facts Statement (KFS), loan agreement and related documents. The lender is <b>${NBFC_PARTNER.name}</b> (CIN ${NBFC_PARTNER.cin}, RBI Registration No. 14.00526). ${BRAND.legalName} (CIN ${BRAND.cin}, ${BRAND.address.line1}, ${BRAND.address.line2} – ${BRAND.address.pin}) provides the technology platform and is not the lender.</p>
    <h2>3. Documents and key terms</h2>
    <ul>
      <li>Sanction letter / loan offer and your customer-specific KFS.</li>
      <li>Executed loan agreement and repayment schedule.</li>
      <li>Interest rate, fees, taxes, APR, net disbursal and total repayment.</li>
      <li>Repayment dates, authorised payment methods and electronic mandate terms, where applicable.</li>
      <li>Applicable penal charges, early repayment and exit terms.</li>
      <li>Privacy notices, consent records, grievance details and other required disclosures.</li>
    </ul>
    <h2>4. Access and review</h2>
    <p>Your sanction letter and KFS are shown to you in your DhanBoost account before you sign, and the signed copy is sent to your registered email. Access controls prevent unauthorised disclosure of customer documents.</p>
    <p>Before accepting, check the lender's identity, approved amount, net disbursal, interest, fees, APR, total repayment, tenure, due dates, charges and grievance information. Ask for clarification before you accept if any material term is unclear.</p>
    <h2>5. Electronic acceptance and record retention</h2>
    <p>Acceptance takes place through an authorised electronic process: you review the document, authenticate, sign electronically and receive an executed copy. Opening a website or viewing a document alone is not acceptance. Acceptance, consent and mandate records are retained as prescribed.</p>
    <p>Keep copies of your KFS, executed agreement, repayment schedule, mandate confirmation, disbursal confirmation, payment receipts and important messages. Never share OTPs, passwords, UPI PINs or other confidential credentials with support staff or anyone else.</p>
    <h2>6. Incorrect or missing documents</h2>
    <p>Report incorrect amounts, rates, fees, taxes, due dates, lender details, missing pages or suspected unauthorised acceptance using the contact details below. Give your loan or application reference and a brief description, but never include passwords, OTPs or PINs. Our team will investigate and coordinate with the lender as needed.</p>
    <h2>7. Amendments and privacy</h2>
    <p>Loan terms are never changed merely by updating website content. Any permitted extension, restructuring or other change follows the agreement, applicable law and any required notice to or consent from you. Loan documents are accessed only by authorised persons for legitimate purposes, in line with our <a href="/privacy" data-link="">Privacy Policy</a>.</p>
    <h2>8. Support and grievance redressal</h2>
    <p>For help with the platform or accessing your documents, contact DhanBoost using the details below. Complaints about contractual terms, lender decisions or regulated lending responsibilities can also be raised through the lender's grievance mechanism on our <a href="/grievance" data-link="">Grievance Redressal</a> page.</p>
    <h2>9. Contact details</h2>
    ${POLICY_CONTACTS}
    <p>Document reference: DB-LGL-003.</p>
  </div></div></div>
</section>`;
