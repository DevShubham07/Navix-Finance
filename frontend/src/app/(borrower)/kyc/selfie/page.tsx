// KYC step: selfie capture (for records only).
// TODO: capture a selfie and store it for record-keeping. NO liveness/face-match
//       API is used. Populate the SelfieCapture domain type.

export default function KycSelfiePage() {
  return (
    <section>
      <h1>Take a selfie</h1>
      <p>We keep this photo on file for verification records only.</p>
    </section>
  );
}
