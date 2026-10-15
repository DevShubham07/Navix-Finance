// Hand-written (not from the design export) — mirrors the markup the other policy pages use.
/* eslint-disable */
import { BRAND } from "@/lib/brand";

export const html = `<section class="page active" id="refund-policy">
  <div class="page-hero"><div class="wrap"><div class="crumb"><a href="/" data-link="">Home</a> &nbsp;/&nbsp; Refund &amp; Cancellation Policy</div><h1>Refund &amp; Cancellation Policy</h1><p>When money comes back to you, how to ask for it, and how long it takes. Effective 20 July 2026.</p></div></div>
  <div class="sec"><div class="wrap"><div class="prose">

    <h2>1. Scope</h2>
    <p>This policy covers money that moves between you and DhanBoost in connection with a salary advance — amounts you pay us, and amounts we release to you. It applies to every applicant and borrower on the platform and sits alongside your loan agreement and Key Fact Statement (KFS). Where this policy and your KFS differ on a specific loan, the KFS governs that loan.</p>

    <h2>2. The principle</h2>
    <p>DhanBoost charges nothing to apply. There is no advance fee, no login fee, no processing charge collected before disbursal, and no fee for a rejected or withdrawn application. The processing fee and GST are <b>deducted from the amount disbursed</b> — they are never collected separately from you. If no money is disbursed, you have paid us nothing, so there is nothing to refund.</p>
    <p>A refund therefore arises only in the narrow cases where money reached us that should not have, or reached the wrong place.</p>

    <h2>3. Where you are in the journey</h2>
    <div class="tbl"><table>
      <thead><tr><th>Stage</th><th>Have you paid anything?</th><th>Can you exit?</th></tr></thead>
      <tbody>
        <tr><td>Application / KYC submitted</td><td>No</td><td>Yes — abandon or ask us to cancel, no charge</td></tr>
        <tr><td>Under credit review</td><td>No</td><td>Yes — ask us to cancel, no charge</td></tr>
        <tr><td>Sanctioned, offer not yet accepted</td><td>No</td><td>Yes — simply do not accept the offer</td></tr>
        <tr><td>Offer accepted and e-signed, not yet disbursed</td><td>No</td><td>Contact us immediately — see 4.1</td></tr>
        <tr><td>Disbursed (loan active)</td><td>Fee + GST already netted off</td><td>No exit; you may prepay at any time with no prepayment charge</td></tr>
        <tr><td>Repaid and closed</td><td>Yes, in full</td><td>Overpayment, if any, is refunded — see 4.3</td></tr>
      </tbody>
    </table></div>

    <h2>4. When a refund is due</h2>

    <h3>4.1 Disbursal failed or went to the wrong account</h3>
    <p>If a transfer is rejected by the bank, or credited to an account that is not yours, nothing is owed by you. No interest accrues and no fee is retained for a transfer that did not reach you. Where a failed amount has been debited at our end, it is returned to source.</p>

    <h3>4.2 Duplicate or mistaken payment by you</h3>
    <p>If you pay the same instalment twice, or pay against a loan that is already closed, the excess is returned to the account you paid from.</p>

    <h3>4.3 Overpayment on closure</h3>
    <p>If the amount you paid exceeds your outstanding on the date of payment — including interest and any late-payment charge — the difference is returned. Alternatively, at your written request, we may hold it against a future advance.</p>

    <h3>4.4 Amount wrongly charged</h3>
    <p>If a charge was applied in error, or in excess of what your Key Fact Statement discloses, it is reversed in full on verification.</p>

    <h3>4.5 Loan cancelled before the money moved</h3>
    <p>If your loan is cancelled after e-signing but before any amount is released, the agreement does not take effect, no interest accrues, and no fee is payable.</p>

    <h2>5. What is not refundable</h2>
    <ul>
      <li><b>Interest already accrued</b> on an advance you have used. Interest is charged for the days the money was with you; repaying early reduces it, but days already elapsed are payable.</li>
      <li><b>Processing fee and GST</b> on a loan that was successfully disbursed. These are consideration for work already performed and are netted off at disbursal.</li>
      <li><b>Late-payment charges</b> correctly levied on an overdue amount.</li>
      <li><b>A disbursed loan you no longer want.</b> Once the money is in your account the advance is live; return it as a prepayment — there is no prepayment charge, and you pay interest only for the days held.</li>
      <li><b>Third-party charges</b> levied by your own bank or payment provider.</li>
    </ul>

    <h2>6. How to request a refund</h2>
    <ol>
      <li>Write to <b>${BRAND.email}</b>, or call ${BRAND.phone} (${BRAND.hours}).</li>
      <li>Give us your registered mobile number, your loan or application reference, the amount, and the date of the transaction.</li>
      <li>Attach proof of payment — a bank statement entry, UPI reference, or payment screenshot.</li>
    </ol>
    <p>We may ask for a bank statement showing the debit before we release money, so that it goes back to the right account.</p>

    <h2>7. Timelines</h2>
    <ul>
      <li><b>Acknowledgement:</b> within <b>48 hours</b> of your request.</li>
      <li><b>Decision:</b> within <b>2 working days</b> of receiving complete details and proof.</li>
      <li><b>Credit:</b> within <b>2 working days</b> of approval. Your bank may take a further working day or two to show it.</li>
    </ul>
    <p>Refunds are returned to the <b>same source</b> the payment came from. Where that is not possible, they are credited to your registered bank account on record. We do not refund in cash or to a third-party account.</p>
    <p>If a refund is declined, we tell you why, in writing.</p>

    <h2>8. Auto-debit</h2>
    <p>DhanBoost does not currently operate any auto-debit, NACH, e-NACH or UPI Autopay mandate. Every repayment is made by you, manually. Nothing is ever pulled from your account without your action, so no mandate cancellation or mandate-related refund arises.</p>

    <h2>9. If you are unhappy with the outcome</h2>
    <p>Escalate through the route set out on our <a href="/grievance" data-link="">Grievance Redressal</a> page — Customer Support, then our Grievance Redressal Officer <b>${BRAND.grievanceOfficer.name}</b> at <b>${BRAND.grievanceOfficer.email}</b> or ${BRAND.grievanceOfficer.phone}, and thereafter the external escalation route described there.</p>

    <div style="text-align:center;margin-top:32px"><a href="/contact" class="btn btn-gold btn-lg" data-link="">Request a refund</a></div>
  </div></div></div>
</section>`;
