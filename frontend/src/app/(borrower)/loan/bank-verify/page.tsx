// Loan step 2: bank verification (penny-drop name match).
// TODO: verify the disbursement account via Fintrix `verification_pennydrop`
//       and confirm the beneficiary name matches. Populate the
//       PennyDropVerification domain type.

export default function LoanBankVerifyPage() {
  return (
    <section>
      <h1>Verify your bank account</h1>
      <p>We&apos;ll send a small deposit to confirm your account and name match.</p>
    </section>
  );
}
