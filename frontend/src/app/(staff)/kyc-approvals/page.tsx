// TODO: KYC approvals queue. List applications whose KYC is pending and let the
// approver accept/reject identity (Aadhaar/PAN/DigiLocker) verification.
// Roles: KYC Approver.
// Backend: GET /api/kyc/pending ; POST /api/kyc/{applicationId}/decision.

export default function KycApprovalsPage() {
  return (
    <section>
      <h1>KYC Approvals</h1>
      <p>Review and decide pending identity verifications.</p>
    </section>
  );
}
