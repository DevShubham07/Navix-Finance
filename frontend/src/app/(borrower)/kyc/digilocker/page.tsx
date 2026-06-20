// KYC step: initiate DigiLocker.
// TODO: call DigiLocker `digilocker_initialize` (X-Client-ID / X-Client-Secret
//       headers) and redirect the borrower to the DigiLocker consent URL.
//       Populate the DigiLockerSession domain type.

export default function KycDigiLockerPage() {
  return (
    <section>
      <h1>Connect DigiLocker</h1>
      <p>You&apos;ll be redirected to DigiLocker to share your Aadhaar securely.</p>
    </section>
  );
}
