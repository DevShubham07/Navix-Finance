// TODO: Credit review detail. Credit Executive reviews income/risk
// (category A/B/C/D affects limit & checks, not price) and recommends; Credit
// Head gives final approval. Loan cap = 25% of monthly salary.
// Separation of duties: recommender (Executive) != approver (Head).
// Roles: Credit Executive (recommend), Credit Head (final approve).
// Backend: GET /api/credit/{applicationId} ;
//   POST /api/credit/{applicationId}/recommend ;
//   POST /api/credit/{applicationId}/approve.

export default async function CreditReviewPage({
  params,
}: {
  params: Promise<{ applicationId: string }>;
}) {
  const { applicationId } = await params;
  return (
    <section>
      <h1>Credit Review</h1>
      <p>Application {applicationId}: review risk, recommend, and approve.</p>
    </section>
  );
}
