// Sign-up step 8: address proof.
// TODO: collect address proof and verify via Fintrix `ent_address_verification`.
//       Populate the AddressVerification domain type, then move to review.

export default function SignupAddressProofPage() {
  return (
    <section>
      <h1>Verify your address</h1>
      <p>Upload a valid address proof so we can confirm where you live.</p>
    </section>
  );
}
