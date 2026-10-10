import * as React from "react";
import { Briefcase, CalendarCheck, CircleCheck, FileText, GraduationCap, IndianRupee, Percent, ShieldCheck, Wallet } from "lucide-react";
import { CalendarTile, ButtonLink, BlueprintGrid, BlueprintItem, Container, FloatTile, IconTile, Section, SectionHead } from "./primitives";
import { AmountCard, CheckTile } from "./fragments";
import { PageHero } from "./page-kit";

/**
 * /products in the landing layout language. Copy: formerly `(marketing)/_content/products.ts`, with
 * the approved accuracy edit to the amount / repayment terms.
 */

const SOON = [
  {
    icon: Wallet,
    title: "Salary Advance",
    text: "Draw a portion of your earned salary instantly, repay on payday. Designed for recurring short-term needs.",
  },
  {
    icon: Briefcase,
    title: "Business Loan",
    text: "Flexible working-capital financing for entrepreneurs and small businesses to fuel growth.",
  },
  {
    icon: GraduationCap,
    title: "Education Loan",
    text: "Affordable funding for courses, certifications and skilling, for studies in India and abroad.",
  },
];

export function ProductsPage() {
  return (
    <div className="lp">
      <PageHero
        trail={[{ label: "Loan Products" }]}
        label="Loan products"
        lead="Loan products"
        muted="built for real life"
        sub="Flexible, short-term financing with transparent terms. More products are on the way. Here's what's live today."
        floats={
          <>
            <FloatTile className="left-[5%] top-[18%]" rotate={-8}>
              <CalendarTile day="30" month="Jun" size={84} />
            </FloatTile>
            <FloatTile className="bottom-[12%] left-[12%]" rotate={6} delay={2}>
              <CheckTile size={70} />
            </FloatTile>
            <FloatTile className="right-[4%] top-[20%]" rotate={5} delay={1}>
              <AmountCard />
            </FloatTile>
          </>
        }
      />

      <Section labelledBy="live-title" className="pb-10 sm:pb-14">
        <Container>
          <article className="reveal relative overflow-hidden rounded-[28px] border-2 border-gold-400 bg-paper p-6 shadow-md sm:p-10">
            <div className="grid gap-10 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)] lg:items-center">
              <div>
                <span className="inline-flex items-center gap-1.5 rounded-full bg-success-50 px-3 py-1 text-[12.5px] font-medium text-success-700">
                  <span aria-hidden className="h-1.5 w-1.5 rounded-full bg-success-600" /> Live now
                </span>
                <h2 id="live-title" className="lp-h2 mt-5 !text-[clamp(1.9rem,3.4vw,2.6rem)]">
                  Instant Personal Loan
                </h2>
                <p className="m-0 mt-4 max-w-[520px] text-[15.5px] leading-[1.65] text-slate">
                  Quick personal loans for any purpose: medical emergencies, education, travel, bills or a cash-flow gap
                  before payday. Fully digital, disbursed by our RBI-registered NBFC partners.
                </p>
                <ButtonLink href="/signup/start" size="lg" arrow className="mt-7">
                  Apply Now
                </ButtonLink>
              </div>
              <dl className="m-0 grid gap-3 sm:grid-cols-3 lg:grid-cols-1 xl:grid-cols-3">
                {[
                  { icon: IndianRupee, k: "Amount", v: "From ₹1,000", s: "up to 25% of your monthly salary. Your approved limit is set by our credit team." },
                  { icon: CalendarCheck, k: "Repayment", v: "Once", s: "on your next salary day, within 40 days." },
                  { icon: Percent, k: "Interest from", v: "1% / day", s: "" },
                ].map(({ icon: Icon, k, v, s }) => (
                  <div key={k} className="rounded-[20px] border border-line bg-grey-50 p-5">
                    <dt className="flex items-center gap-2 text-[12.5px] font-semibold uppercase tracking-[0.08em] text-slate">
                      <Icon aria-hidden size={15} className="text-gold-dark" /> {k}
                    </dt>
                    <dd className="m-0 mt-3">
                      <span className="lp-fig block text-[26px] text-ink">{v}</span>
                      {s && <span className="mt-1.5 block text-[13px] leading-[1.5] text-slate">{s}</span>}
                    </dd>
                  </div>
                ))}
              </dl>
            </div>
          </article>

          <ul className="m-0 mt-5 grid list-none gap-5 p-0 md:grid-cols-3">
            {SOON.map(({ icon: Icon, title, text }, i) => (
              <li key={title} className={`reveal ${i ? `d${i}` : ""} lp-card rounded-[24px] p-6 sm:p-7`}>
                <div className="flex items-start justify-between gap-3">
                  <IconTile size={48} className="text-ink">
                    <Icon aria-hidden size={21} strokeWidth={1.8} />
                  </IconTile>
                  <span className="rounded-full bg-grey-100 px-3 py-1 text-[12px] font-medium text-slate">Coming soon</span>
                </div>
                <h3 className="lp-h3 mt-6">{title}</h3>
                <p className="m-0 mt-2 text-[14.5px] leading-[1.6] text-slate">{text}</p>
              </li>
            ))}
          </ul>
        </Container>
      </Section>

      <Section labelledBy="promise-title" className="pt-10 sm:pt-14">
        <Container>
          <SectionHead label="Every product includes" id="promise-title" lead="The DhanBoost promise" />
          <BlueprintGrid cols={4} className="reveal mt-14">
            <BlueprintItem icon={<CircleCheck size={22} strokeWidth={1.9} />} title="No advance fees">
              You never pay anything upfront.
            </BlueprintItem>
            <BlueprintItem icon={<Percent size={22} strokeWidth={1.9} />} title="No pre-closure charge">
              Repay early and save.
            </BlueprintItem>
            <BlueprintItem icon={<FileText size={22} strokeWidth={1.9} />} title="Key Fact Statement">
              Full cost shown upfront.
            </BlueprintItem>
            <BlueprintItem icon={<ShieldCheck size={22} strokeWidth={1.9} />} title="RBI-aligned">
              Through regulated NBFCs.
            </BlueprintItem>
          </BlueprintGrid>
        </Container>
      </Section>
    </div>
  );
}
