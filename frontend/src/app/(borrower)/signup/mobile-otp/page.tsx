// Sign-up step 2: mobile number + NAVIX-owned OTP (with resend).
// TODO: send/verify OTP via backend IAM endpoints (NAVIX own OTP, not a
//       third party); support resend cooldown; populate MobileVerification.

export default function SignupMobileOtpPage() {
  return (
    <section>
      <h1>Verify your mobile number</h1>
      <p>Enter the OTP we sent to your phone. You can request a new code if needed.</p>
    </section>
  );
}
