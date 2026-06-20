// Sign-up step 3: employment status + UAN.
// TODO: capture employment status and UAN; UAN-based EPFO lookup API is
//       RESERVED for now. Populate the EmploymentDetails domain type.

export default function SignupEmploymentPage() {
  return (
    <section>
      <h1>Your employment details</h1>
      <p>Tell us about your job and provide your UAN if available.</p>
    </section>
  );
}
