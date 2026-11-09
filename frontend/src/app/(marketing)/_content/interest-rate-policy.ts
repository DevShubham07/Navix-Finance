/* eslint-disable */
import { NBFC_PARTNER, POLICY_DRAFT_NOTICE } from "@/lib/brand";

export const html = `<section class="page active" id="interest-rate-policy">
  <div class="page-hero"><div class="wrap"><div class="crumb"><a href="/" data-link="">Home</a> &nbsp;/&nbsp; Interest Rate Policy</div><h1>Interest Rate Policy</h1><p>How interest and charges are set on loans offered through DhanBoost. Last updated: November 2026.</p></div></div>
  <div class="sec"><div class="wrap"><div class="prose">
    ${POLICY_DRAFT_NOTICE}
    <h2>1. Who sets the rate</h2>
    <p>Loans offered through DhanBoost are made by ${NBFC_PARTNER.name}, an RBI-registered NBFC. The lender sets the interest rate and charges. The rate, fees and total repayable for your loan are set out in your Key Fact Statement before you accept.</p>
    <h2>2. Current rates and charges</h2>
    <ul>
      <li><b>Interest:</b> 1% per day on the principal, for the actual number of days the loan is held (equivalent to 365% a year, before fees). Interest does not compound.</li>
      <li><b>Processing fee:</b> 10% of the loan amount, deducted from the amount disbursed.</li>
      <li><b>GST:</b> 18% on the processing fee, deducted from the amount disbursed.</li>
      <li><b>Late-payment charge:</b> 2% per day on the principal from the day after the due date, capped at 30 days. No further interest is charged on this amount.</li>
      <li><b>Prepayment:</b> no charge. Interest is payable only up to the day you repay.</li>
      <li><b>Credit card fee:</b> if you choose to repay online by credit card, a card fee is added to your payment and shown before you pay. It is not part of your repayment. There is no such fee for UPI from a bank account, net banking or debit card.</li>
    </ul>
    <h2>3. How risk is treated</h2>
    <p>The same interest rate and processing fee apply to every borrower. Your assessed risk, based on income, credit history and verification results, decides whether a loan is offered and how much you can borrow, up to 25% of your monthly salary. It does not change your rate.</p>
    <h2>4. Worked example</h2>
    <p>For a ₹10,000 loan held for 27 days, the processing fee is ₹1,000 and GST is ₹180, so you receive ₹8,820. Interest is ₹2,700, so you repay ₹12,700. If you repay on day 20 instead, interest is ₹2,000 and you repay ₹12,000. This example is illustrative.</p>
    <h2>5. Annual Percentage Rate</h2>
    <p>The Annual Percentage Rate (APR) combines interest and the processing fee, excludes GST, and depends on your loan amount and tenure. The APR for your loan is stated in your Key Fact Statement.</p>
    <h2>6. Changes</h2>
    <p>Any change in rates will be published on this page. The terms in your signed Key Fact Statement continue to apply to your loan.</p>
  </div></div></div>
</section>`;
