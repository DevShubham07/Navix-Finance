import { Mail, Phone, ShieldAlert, ChevronDown } from "lucide-react";
import { BRAND } from "@/lib/brand";
import { PaymentSafetyTicker } from "@/components/site/payment-safety-ticker";

/**
 * Borrower support + Help & FAQ. Serves both the "Support" and "Help & FAQ"
 * account-menu items (the FAQ section carries id="faq" so /support#faq jumps to it).
 * Static content — product facts mirror CLAUDE.md §1.
 */

// Single source of truth — these used to be hardcoded copies that could drift from lib/brand.ts.
const SUPPORT_EMAIL = BRAND.email;
const SUPPORT_PHONE = BRAND.phone;
const GRIEVANCE = BRAND.grievanceOfficer;

const FAQ: Array<{ q: string; a: string }> = [
  {
    q: "How much can I borrow?",
    a: "You're eligible for an instant loan of up to ₹10,00,000, with a minimum advance of ₹1,000. One price applies to everyone.",
  },
  {
    q: "What does it cost?",
    a: "A one-time processing fee of 10% of the loan amount, plus 18% GST on that fee. Both are deducted upfront from your disbursal, so you receive the principal minus fee and GST.",
  },
  {
    q: "How is interest charged?",
    a: "Interest is 1% per day on the principal, charged over the actual number of days you hold the advance.",
  },
  {
    q: "When do I repay?",
    a: "It's a single repayment on your salary-linked due date — your next salary credit, always within 40 days of disbursal. No EMIs.",
  },
  {
    q: "Can I pay early?",
    a: "Yes. Prepayment is allowed any time and reduces your interest to only the day you pay — there's no prepayment penalty, so paying early saves you money.",
  },
  {
    q: "What happens if I'm late?",
    a: "A late penalty of 2% per day on the principal applies, capped at 30 days. Clearing the balance promptly stops the penalty from growing.",
  },
  {
    q: "How do I make a payment?",
    a: "Pay manually by UPI or bank transfer and submit the transaction reference. Our accounts team verifies it before your balance updates.",
  },
];

export default function SupportPage() {
  return (
    <div className="container max-w-content py-10">
      <div className="mb-7">
        <h1 className="mb-0">Support</h1>
        <p className="mt-1 text-muted">We&apos;re here to help. Reach us directly, raise a grievance, or browse the FAQ.</p>
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <a
          href={`mailto:${SUPPORT_EMAIL}`}
          className="flex items-start gap-3 rounded border border-line bg-white p-5 shadow-sm transition-colors hover:border-navy"
        >
          <span className="grid h-11 w-11 flex-shrink-0 place-items-center rounded-full bg-navy-tint text-navy">
            <Mail size={20} />
          </span>
          <div>
            <div className="text-sm font-semibold text-navy">Email us</div>
            <div className="text-sm text-muted">{SUPPORT_EMAIL}</div>
            <div className="mt-0.5 text-xs text-muted">We reply within one business day.</div>
          </div>
        </a>

        <a
          href={`tel:${SUPPORT_PHONE.replace(/\s/g, "")}`}
          className="flex items-start gap-3 rounded border border-line bg-white p-5 shadow-sm transition-colors hover:border-navy"
        >
          <span className="grid h-11 w-11 flex-shrink-0 place-items-center rounded-full bg-navy-tint text-navy">
            <Phone size={20} />
          </span>
          <div>
            <div className="text-sm font-semibold text-navy">Call us</div>
            <div className="text-sm text-muted">{SUPPORT_PHONE}</div>
            <div className="mt-0.5 text-xs text-muted">Mon–Sat, 9am–7pm IST.</div>
          </div>
        </a>
      </div>

      <PaymentSafetyTicker className="mt-6 rounded" />

      {/* Grievance redressal — names the officer required under the Consumer Protection Act, 2019. */}
      <div className="mt-4 flex items-start gap-3 rounded border border-gold-soft bg-gold-50/60 p-5">
        <span className="grid h-11 w-11 flex-shrink-0 place-items-center rounded-full bg-gold-soft text-gold-dark">
          <ShieldAlert size={20} />
        </span>
        <div>
          <div className="text-sm font-semibold text-ink">Raise a grievance</div>
          <p className="text-sm text-muted">
            Not satisfied with a resolution? Escalate to our Grievance Redressal Officer,{" "}
            <strong className="text-ink">{GRIEVANCE.name}</strong>, and we&apos;ll respond within the
            regulatory timeline.
          </p>
          <p className="mt-1 text-sm text-muted">
            {GRIEVANCE.email} · {GRIEVANCE.phone}
          </p>
          <a
            href={`mailto:${GRIEVANCE.email}?subject=${encodeURIComponent("Grievance — DhanBoost")}`}
            className="btn btn-outline btn-sm mt-3"
          >
            <ShieldAlert size={15} /> File a grievance
          </a>
        </div>
      </div>

      <section id="faq" className="mt-10 scroll-mt-24">
        <h2 className="mb-3 font-serif text-xl font-semibold text-navy">Help &amp; FAQ</h2>
        <div className="divide-y divide-line overflow-hidden rounded border border-line bg-white shadow-sm">
          {FAQ.map((item) => (
            <details key={item.q} className="group">
              <summary className="flex cursor-pointer items-center justify-between gap-4 px-5 py-4 text-sm font-semibold text-ink hover:bg-grey-100">
                {item.q}
                <ChevronDown size={16} className="flex-shrink-0 text-muted transition-transform group-open:rotate-180" />
              </summary>
              <p className="px-5 pb-4 text-sm text-muted">{item.a}</p>
            </details>
          ))}
        </div>
      </section>
    </div>
  );
}
