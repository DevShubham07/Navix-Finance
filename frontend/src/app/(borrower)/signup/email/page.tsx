// Sign-up step 5: personal + official email.
// TODO: collect personal and official emails, verify the official email via
//       Fintrix `cv_email_verification`, populate the EmailVerification type.

export default function SignupEmailPage() {
  return (
    <section>
      <h1>Your email addresses</h1>
      <p>Provide your personal and official emails so we can verify your employment.</p>
    </section>
  );
}
