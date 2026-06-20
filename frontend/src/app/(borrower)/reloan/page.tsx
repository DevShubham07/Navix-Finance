// Returning customer: instant re-loan (light re-checks, no full KYC).
// TODO: for borrowers with good repayment history, run light re-checks (bank,
//       bureau, OTP), refresh eligibility/limit, then reuse the 3-step loan flow
//       (apply -> documents -> bank-verify). Surface any pre-approved offer.

export default function ReloanPage() {
  return (
    <section>
      <h1>Borrow again</h1>
      <p>
        Welcome back. Returning customers can re-borrow quickly with light
        re-checks instead of full KYC.
      </p>
    </section>
  );
}
