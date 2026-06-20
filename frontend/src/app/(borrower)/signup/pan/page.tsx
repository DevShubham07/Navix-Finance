// Sign-up step 1: PAN capture & verification.
// TODO: collect PAN, call Fintrix `pan_comprehensive`, populate the
//       PanVerification domain type, then advance to mobile-otp.

export default function SignupPanPage() {
  return (
    <section>
      <h1>Enter your PAN</h1>
      <p>We use your PAN to verify your identity and fetch your basic details.</p>
    </section>
  );
}
