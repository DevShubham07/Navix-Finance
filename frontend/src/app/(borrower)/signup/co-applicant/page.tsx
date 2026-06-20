// Sign-up (conditional): secondary applicant / co-applicant.
// Required for higher-risk categories (C/D). The co-applicant shares repayment
// responsibility and is verified through the same KYC/income path.
// TODO: collect co-applicant PAN/mobile/name + relationship, run KYC, and link
//       to the primary borrower. Populate the CoApplicant domain type.

export default function SignupCoApplicantPage() {
  return (
    <section>
      <h1>Add a co-applicant</h1>
      <p>
        For some applications a co-applicant is required. They share repayment
        responsibility and complete the same verification you did.
      </p>
    </section>
  );
}
