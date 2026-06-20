// TODO: Disbursement authorisation. Disbursement Head reviews approved loans and
// authorises the release (the final maker-checker step before money moves).
// Separation of duties: releaser (Disbursement Head) != credit approver.
// Roles: Disbursement Head.
// Backend: GET /api/disbursement/pending ;
//   POST /api/disbursement/{loanId}/authorise.

export default function DisbursementPage() {
  return (
    <section>
      <h1>Disbursement Authorisation</h1>
      <p>Authorise release of approved loans for payout.</p>
    </section>
  );
}
