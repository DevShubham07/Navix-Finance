/* eslint-disable */
import { BRAND, NBFC_PARTNER, POLICY_DRAFT_NOTICE } from "@/lib/brand";

export const html = `<section class="page active" id="kyc-aml-policy">
  <div class="page-hero"><div class="wrap"><div class="crumb"><a href="/" data-link="">Home</a> &nbsp;/&nbsp; KYC &amp; AML Policy</div><h1>KYC &amp; AML Policy</h1><p>How identity is verified and how misuse of the financial system is prevented. Last updated: November 2026.</p></div></div>
  <div class="sec"><div class="wrap"><div class="prose">
    ${POLICY_DRAFT_NOTICE}
    <h2>1. Purpose</h2>
    <p>This policy summarises the Know Your Customer (KYC) and Anti-Money Laundering (AML) measures that apply to loans offered through DhanBoost. ${NBFC_PARTNER.name}, as the lender, is responsible for meeting the requirements of the Prevention of Money-Laundering Act, 2002 and the Reserve Bank of India's KYC directions. ${BRAND.legalName} carries out the digital steps below on the lender's behalf.</p>
    <h2>2. Who we verify</h2>
    <p>Every applicant must complete identity verification before a loan is disbursed. Loans are offered only to individuals applying for themselves.</p>
    <h2>3. What we collect and check</h2>
    <ul>
      <li>PAN, verified against official records.</li>
      <li>Aadhaar number and images of both sides of your Aadhaar and PAN cards.</li>
      <li>Records you choose to share through DigiLocker.</li>
      <li>A selfie, to confirm you are the person applying.</li>
      <li>Your current address, employment and income details.</li>
      <li>Your bank account, verified by a small test transfer or, where that is not possible, bank proof reviewed by our team.</li>
    </ul>
    <h2>4. Fraud and mismatches</h2>
    <p>If the details you give do not match official records, your application may be reviewed by our team or rejected. Applications may also be refused where the same identity appears to be used by more than one person.</p>
    <h2>5. Movement of funds</h2>
    <p>Loans are paid only into the bank account verified for your application. Repayments must be made only through the channels shown on your account.</p>
    <h2>6. Monitoring and reporting</h2>
    <p>The lender is responsible for monitoring transactions, reporting as required to the Financial Intelligence Unit – India, and retaining KYC and transaction records for the period required by law. Principal Officer: [MLB to confirm].</p>
    <h2>7. Keeping your details current</h2>
    <p>You may be asked to update or re-verify your details during your loan or when you apply again.</p>
    <h2>8. Report a concern</h2>
    <p>If you suspect fraud or misuse of your identity, write to <b>${BRAND.fraudEmail}</b>. Never share your OTP, password or UPI PIN with anyone.</p>
  </div></div></div>
</section>`;
