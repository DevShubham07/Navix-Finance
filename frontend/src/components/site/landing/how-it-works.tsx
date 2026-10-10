import * as React from "react";
import { CircleCheck, Clock, Fingerprint, IdCard, Landmark, PenLine, ShieldCheck } from "lucide-react";
import { GoalProgress } from "@/components/kit";
import { cn } from "@/lib/utils";
import { AmountCard, JourneyCard, RepaymentCard, StickyNote } from "./fragments";
import { CtaBand, PageHero } from "./page-kit";
import { ButtonLink, CheckList, Container, FloatTile, IconTile, Section, SectionHead } from "./primitives";

/**
 * /how-it-works in the landing layout language. Copy: formerly `(marketing)/_content/how-it-works.ts`
 * (with the approved accuracy edits: credit-team review, UPI / bank-transfer repayment, no
 * credit-score claim). Illustrations use the product's worked example (₹10,000 → ₹8,820 net).
 */

const STATS = [
  { b: "2 min", s: "Application time" },
  { b: "Reviewed", s: "by our credit team" },
  { b: "24–48h", s: "To disbursal" },
  { b: "100%", s: "Digital process" },
];

const STEPS = [
  {
    n: "01",
    title: "Apply online in minutes",
    text: "Tell us how much you need and for how long, then share basic personal and income details. The whole form takes about two minutes on any device.",
    art: <ApplyArt />,
  },
  {
    n: "02",
    title: "Instant digital verification",
    text: "Complete paperless KYC with PAN and Aadhaar. Our partner NBFC runs a quick eligibility and affordability check, reviewed by our credit team.",
    art: <ChecksArt />,
  },
  {
    n: "03",
    title: "Review & accept your offer",
    text: "See your exact interest, APR and total repayment in a clear Key Fact Statement. Like what you see? E-sign the agreement securely.",
    art: <KfsCardArt />,
  },
  {
    n: "04",
    title: "Money in your bank",
    text: "The partner NBFC disburses funds directly to your verified bank account, typically within 24–48 hours. Repay by UPI or bank transfer: upload the receipt and we'll confirm it.",
    art: <FundsArt />,
  },
];

export function HowItWorksPage() {
  return (
    <div className="lp">
      <PageHero
        trail={[{ label: "How It Works" }]}
        label="How it works"
        lead="From application"
        muted="to your account"
        sub="A guided, fully online journey. No branch visits, no paperwork, just a few simple steps and a transparent decision."
        floats={
          <>
            <FloatTile className="left-[4%] top-[14%]" rotate={-5}>
              <JourneyCard />
            </FloatTile>
            <FloatTile className="bottom-[10%] left-[9%]" rotate={7} delay={2}>
              <IconTile size={70} className="text-gold-dark">
                <Fingerprint size={30} strokeWidth={1.7} />
              </IconTile>
            </FloatTile>
            <FloatTile className="right-[4%] top-[12%]" rotate={6} delay={1}>
              <RepaymentCard />
            </FloatTile>
            <FloatTile className="bottom-[12%] right-[8%]" rotate={-8} delay={3}>
              <IconTile size={66} className="text-ink">
                <PenLine size={27} strokeWidth={1.7} />
              </IconTile>
            </FloatTile>
          </>
        }
      >
        <dl className="mx-auto grid max-w-[640px] grid-cols-2 gap-px overflow-hidden rounded-[22px] border border-line bg-line sm:grid-cols-4">
          {STATS.map((s) => (
            <div key={s.s} className="flex flex-col-reverse bg-paper px-3 py-4">
              <dt className="text-[12px] leading-tight text-slate">{s.s}</dt>
              <dd className="lp-fig m-0 mb-1.5 text-[22px] text-ink">{s.b}</dd>
            </div>
          ))}
        </dl>
      </PageHero>

      <Section labelledBy="steps-title">
        <Container>
          <SectionHead label="Four steps" id="steps-title" lead="A guided, fully online journey" muted="no paperwork" />
          <ol className="m-0 mt-14 grid list-none gap-4 p-0 sm:mt-16 md:grid-cols-2">
            {STEPS.map((s, i) => (
              <li key={s.n} className={cn("reveal lp-card flex min-w-0 flex-col rounded-[26px] p-2.5", i % 2 === 1 && "d1")}>
                <div aria-hidden className="relative h-[250px] overflow-hidden rounded-[20px] border border-line bg-grey-50">
                  {s.art}
                </div>
                <div className="flex gap-4 px-3.5 pb-5 pt-5">
                  <span className="lp-fig shrink-0 pt-0.5 text-[15px] text-gold-dark">{s.n}</span>
                  <div className="min-w-0">
                    <h3 className="lp-h3">{s.title}</h3>
                    <p className="m-0 mt-1.5 text-[14.5px] leading-[1.6] text-slate">{s.text}</p>
                  </div>
                </div>
              </li>
            ))}
          </ol>
        </Container>
      </Section>

      <div className="px-3 sm:px-5">
        <div className="mx-auto max-w-[1400px] rounded-[24px] border border-line bg-surface sm:rounded-[32px]">
          <Section labelledBy="elig-title">
            <Container className="grid items-start gap-10 lg:grid-cols-2 lg:gap-16">
              <div className="reveal">
                <SectionHead
                  align="left"
                  label="Eligibility"
                  id="elig-title"
                  lead="Who can apply"
                  sub="Final eligibility is determined by the partner NBFC's credit policy."
                />
                <CheckList
                  className="mt-8 max-w-[460px] [&_li]:text-[15.5px] [&_li]:text-ink"
                  items={[
                    "Indian citizen, aged 21–58",
                    "Salaried with net monthly income ≥ ₹40,000",
                    "Valid PAN & Aadhaar for KYC",
                    "An active bank account in your name",
                  ]}
                />
              </div>
              <div className="reveal d1 lp-card rounded-[28px] p-7 sm:p-9">
                <p className="m-0 text-[13px] font-semibold uppercase tracking-[0.08em] text-gold-dark">What you&apos;ll need</p>
                <h3 className="lp-h3 mt-3 !text-[24px]">Documents, all digital</h3>
                <ul className="m-0 mt-6 grid list-none gap-3 p-0">
                  {[
                    { icon: IdCard, b: "PAN card", s: "For identity & credit check" },
                    { icon: Fingerprint, b: "Aadhaar", s: "For paperless e-KYC" },
                    { icon: Landmark, b: "Bank details", s: "For salary check & disbursal" },
                  ].map(({ icon: Icon, b, s }) => (
                    <li key={b} className="flex items-center gap-3.5 rounded-[16px] border border-line bg-grey-50 px-4 py-3">
                      <span className="grid h-10 w-10 shrink-0 place-items-center rounded-[12px] bg-gold-50 text-gold-dark">
                        <Icon aria-hidden size={18} strokeWidth={1.9} />
                      </span>
                      <span className="leading-tight">
                        <span className="block text-[15px] font-medium text-ink">{b}</span>
                        <span className="block text-[13px] text-slate">{s}</span>
                      </span>
                    </li>
                  ))}
                </ul>
                <ButtonLink href="/signup/start" tone="ink" size="lg" arrow className="mt-7 w-full">
                  Start your application
                </ButtonLink>
              </div>
            </Container>
          </Section>
        </div>
      </div>

      <div className="pt-[clamp(56px,7vw,96px)]">
        <CtaBand
          id="how-cta"
          lead="It only takes two minutes"
          sub="Check your eligibility now. It's free, and there's never an advance fee."
          primary={{ href: "/signup/start", label: "Apply Now" }}
          secondary={{ href: "/calculator", label: "Calculate repayment" }}
        />
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ step illustrations */

function ApplyArt() {
  return (
    <div className="absolute inset-0 grid place-items-center px-6">
      <div className="lp-tile w-full max-w-[300px] rounded-[18px] p-4">
        <div className="flex items-center justify-between text-[12.5px]">
          <span className="text-slate">Loan amount</span>
          <span className="lp-fig text-[20px] text-ink">₹10,000</span>
        </div>
        <div className="relative mt-3 h-2 rounded-full bg-grey-200">
          <span className="absolute inset-y-0 left-0 w-[38%] rounded-full bg-gold" />
          <span className="absolute left-[38%] top-1/2 h-4 w-4 -translate-x-1/2 -translate-y-1/2 rounded-full border-[3px] border-paper bg-gold shadow-sm" />
        </div>
        <div className="mt-4 grid gap-2 text-[12px] text-slate">
          <span className="rounded-[10px] border border-line bg-grey-50 px-3 py-2">Purpose · Medical emergency</span>
          <span className="rounded-[10px] border border-line bg-grey-50 px-3 py-2">Repayment · on your salary day</span>
        </div>
        <span className="mt-3 block rounded-full bg-gold py-2 text-center text-[12.5px] font-semibold text-ink">Continue →</span>
      </div>
    </div>
  );
}

function ChecksArt() {
  return (
    <div className="absolute inset-0 flex flex-col justify-center gap-2 px-7">
      {[
        { k: "PAN", t: "Identity verified" },
        { k: "AADHAAR", t: "e-KYC complete" },
        { k: "BANK", t: "Salary confirmed" },
      ].map((c) => (
        <div key={c.k} className="flex items-center gap-2.5 rounded-[12px] border border-line bg-paper px-3 py-2 text-[12.5px] text-ink">
          <span className="w-[62px] rounded-md bg-navy px-1.5 py-0.5 text-center text-[9.5px] font-semibold tracking-[0.06em] text-white">{c.k}</span>
          <span className="flex-1">{c.t}</span>
          <CircleCheck size={16} className="text-success-600" strokeWidth={2.2} />
        </div>
      ))}
      <GoalProgress ratio={0.8} label="Credit review" trailing="Reviewed by our credit team" tone="light" className="mt-1.5" />
    </div>
  );
}

function KfsCardArt() {
  return (
    <div className="absolute inset-0 grid place-items-center px-6">
      <div className="lp-tile relative w-full max-w-[300px] rounded-[18px] p-4 text-[12.5px]">
        <p className="m-0 mb-2 flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-[0.08em] text-slate">
          <ShieldCheck size={13} className="text-gold-dark" /> Key Fact Statement
        </p>
        {[
          ["You borrow", "₹10,000"],
          ["Fee + GST (deducted)", "₹1,180"],
          ["Interest · 27 days", "₹2,700"],
        ].map(([k, v]) => (
          <div key={k} className="flex justify-between border-b border-line py-1.5">
            <span className="text-slate">{k}</span>
            <span className="tabular-nums text-ink">{v}</span>
          </div>
        ))}
        <div className="flex justify-between pt-2 font-semibold text-ink">
          <span>You repay</span>
          <span className="tabular-nums">₹12,700</span>
        </div>
        <svg viewBox="0 0 200 40" className="mt-2 h-7 w-32 text-ink" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
          <path d="M6 28 C 26 6 44 34 64 18 C 84 2 104 4 132 24 C 150 36 176 18 194 14" />
        </svg>
        <span className="absolute bottom-4 right-4 inline-flex items-center gap-1 rounded-full bg-success-50 px-2 py-0.5 text-[10.5px] font-medium text-success-700">
          <PenLine size={11} /> e-signed
        </span>
      </div>
    </div>
  );
}

function FundsArt() {
  return (
    <div className="absolute inset-0 grid place-items-center">
      <div className="relative">
        <AmountCard />
        <div className="absolute -right-16 -top-10 rotate-6 scale-[.8]">
          <StickyNote className="w-[150px] px-4 pb-4 pt-6 [&_p]:text-[14px]">Repay once, on your salary day.</StickyNote>
        </div>
        <span className="absolute -bottom-5 -left-8 inline-flex items-center gap-1.5 rounded-full border border-line bg-paper px-3 py-1.5 text-[11.5px] text-slate shadow-sm">
          <Clock size={13} className="text-gold-dark" /> Typically 24–48 hours
        </span>
      </div>
    </div>
  );
}
