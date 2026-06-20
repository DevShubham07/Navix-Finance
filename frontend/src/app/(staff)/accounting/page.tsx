// TODO: Accounting confirmation. Accountant manually confirms the bank transfer
// has gone out, which activates the loan and starts interest accrual.
// Net to customer = principal - (10% processing fee + 18% GST on the fee).
// Roles: Accountant.
// Backend: GET /api/accounting/awaiting-transfer ;
//   POST /api/accounting/{loanId}/confirm-transfer.

export default function AccountingPage() {
  return (
    <section>
      <h1>Accounting · Transfer Confirmation</h1>
      <p>Confirm completed bank transfers to activate loans.</p>
    </section>
  );
}
