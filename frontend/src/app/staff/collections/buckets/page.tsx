// TODO: Live DPD buckets overview. Collections Head sees loans grouped by
// days-past-due buckets (late penalty 2%/day capped at 30 days, then
// escalates to collections).
// Roles: Collections Head.
// Backend: GET /api/collections/buckets (live DPD aggregation).

export default function CollectionsBucketsPage() {
  return (
    <section>
      <h1>Collections · DPD Buckets</h1>
      <p>Live days-past-due buckets across the overdue portfolio.</p>
    </section>
  );
}
