import Link from "next/link";

// Public landing / marketing page for borrowers.
// TODO: replace placeholder copy and layout with the real marketing design.
export default function HomePage() {
  return (
    <main className="mx-auto flex min-h-screen max-w-3xl flex-col items-center justify-center gap-8 px-6 py-16 text-center">
      <section className="flex flex-col items-center gap-4">
        <h1 className="text-4xl font-bold text-navix-900">NAVIX Finance</h1>
        <p className="text-lg text-gray-600">
          Salary-linked, single-repayment loans. Borrow up to 25% of your
          monthly salary and repay it all on your next salary day — no EMIs,
          prepay anytime with no penalty.
        </p>
      </section>

      <ul className="flex flex-col gap-2 text-sm text-gray-500">
        <li>Up-front 10% processing fee (+18% GST on the fee)</li>
        <li>1% per day interest, calculated only for the days you borrow</li>
        <li>Single repayment on your salary day</li>
      </ul>

      <div className="flex gap-4">
        <Link
          href="/signup/pan"
          className="rounded-md bg-navix-900 px-6 py-3 font-medium text-white hover:bg-navix-800"
        >
          Get started
        </Link>
        <Link
          href="/login"
          className="rounded-md border border-navix-900 px-6 py-3 font-medium text-navix-900 hover:bg-navix-50"
        >
          Log in
        </Link>
      </div>
    </main>
  );
}
