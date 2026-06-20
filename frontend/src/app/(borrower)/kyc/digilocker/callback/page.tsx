// KYC step: DigiLocker callback handler.
// TODO: on return from DigiLocker, poll `digilocker_status` and fetch the
//       `aadhar_xml` document. Populate the DigiLockerKyc domain type and
//       advance to the selfie step.

export default function KycDigiLockerCallbackPage() {
  return (
    <section>
      <h1>Finishing DigiLocker verification</h1>
      <p>Please wait while we confirm your DigiLocker consent and fetch your details.</p>
    </section>
  );
}
