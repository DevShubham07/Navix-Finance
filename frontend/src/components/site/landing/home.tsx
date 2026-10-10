import * as React from "react";
import {
  Banknote,
  CircleHelp,
  Clock,
  Ellipsis,
  FileText,
  Fingerprint,
  FolderLock,
  Gauge,
  Globe,
  HeartHandshake,
  IdCard,
  IndianRupee,
  Landmark,
  Lock,
  MessageSquareQuote,
  PenLine,
  Plus,
  RefreshCw,
  ShieldCheck,
  Smartphone,
  Users,
  Zap,
} from "lucide-react";
import { Avatar } from "@/components/kit";
import { BRAND } from "@/lib/brand";
import { cn } from "@/lib/utils";
import {
  AmountCard,
  AppIcon,
  CheckTile,
  DocsArt,
  InterestArt,
  JourneyCard,
  KfsArt,
  RepaymentCard,
  SalaryArt,
  StickyNote,
  SupportArt,
  VerifiedCard,
  VerifyArt,
} from "./fragments";
import { MiniDashboard } from "./mini-dashboard";
import {
  ArrowLink,
  BentoCard,
  BlueprintGrid,
  BlueprintItem,
  ButtonLink,
  CalendarTile,
  Container,
  FloatTile,
  Headline,
  IconTile,
  InsetPanel,
  PlanCard,
  QuoteCard,
  Section,
  SectionHead,
} from "./primitives";

/**
 * The home page in the landing layout language. Copy is the site's existing copy (formerly
 * `(marketing)/_content/home.ts`, plus reviews/about/faq); pricing is the product's real cost
 * structure and its worked example.
 */
export function HomePage() {
  return (
    <div className="lp">
      <Hero />
      <Solutions />
      <Features />
      <Verification />
      <Reviews />
      <Pricing />
      <Faq />
    </div>
  );
}

/* ------------------------------------------------------------------ hero */

function Hero() {
  return (
    <div className="px-3 pt-3 sm:px-5 sm:pt-4">
      <InsetPanel className="mx-auto max-w-[1400px]">
        {/* floating fragments — wide screens only, kept clear of the centred copy */}
        <div aria-hidden className="pointer-events-none absolute inset-0 hidden xl:block">
          <FloatTile className="left-[4.5%] top-[11%]" rotate={-7} delay={0}>
            <StickyNote>
              Repay once —
              <br />
              on your salary day.
            </StickyNote>
          </FloatTile>
          <FloatTile className="left-[16.5%] top-[45%]" rotate={9} delay={2}>
            <CheckTile size={78} />
          </FloatTile>
          <FloatTile className="-left-[1.5%] bottom-[9%]" rotate={-4} delay={3}>
            <JourneyCard />
          </FloatTile>
          <FloatTile className="right-[4%] top-[9%]" rotate={6} delay={1}>
            <RepaymentCard />
          </FloatTile>
          <FloatTile className="right-[17%] top-[47%]" rotate={-8} delay={4}>
            <IconTile size={70} className="text-gold-dark">
              <PenLine size={28} strokeWidth={1.8} />
            </IconTile>
          </FloatTile>
          <FloatTile className="-right-[1.5%] bottom-[8%]" rotate={-3} delay={2}>
            <VerifiedCard />
          </FloatTile>
        </div>

        <div className="relative mx-auto flex max-w-[760px] flex-col items-center px-5 pb-14 pt-14 text-center sm:pb-20 sm:pt-20 xl:pb-[136px] xl:pt-[104px]">
          <div className="lp-enter">
            <AppIcon size={76} />
          </div>
          <Headline
            as="h1"
            size="h1"
            lead="Instant personal loans."
            muted="Fully digital. Fairly priced."
            className="lp-enter mt-8 [--lp-e:.08s]"
          />
          <p className="lp-enter m-0 mt-6 max-w-[560px] text-[16px] leading-[1.6] text-slate [--lp-e:.16s] sm:text-[17px]">
            A paperless application and transparent terms, from first tap to funds in your account. Borrow ₹5,000 to
            ₹10,00,000 with no advance fees, ever.
          </p>
          <div className="lp-enter mt-8 flex flex-wrap items-center justify-center gap-3 [--lp-e:.24s]">
            <ButtonLink href="/signup/start" size="lg" arrow>
              Apply for a Loan
            </ButtonLink>
            <ButtonLink href="/how-it-works" size="lg" tone="outline">
              How It Works
            </ButtonLink>
          </div>
          <ul className="lp-enter m-0 mt-8 flex list-none flex-wrap justify-center gap-x-6 gap-y-2 p-0 text-[13px] text-slate [--lp-e:.32s]">
            {[
              [Zap, "Fully digital", "Apply in minutes"],
              [ShieldCheck, "Transparent terms", "No hidden fees"],
              [Clock, "No advance fees", "Ever"],
            ].map(([Icon, b, s]) => {
              const I = Icon as typeof Zap;
              return (
                <li key={b as string} className="flex items-center gap-1.5">
                  <I aria-hidden size={14} className="text-gold-dark" strokeWidth={2.2} />
                  <span>
                    <b className="font-medium text-ink">{b as string}</b> · {s as string}
                  </span>
                </li>
              );
            })}
          </ul>

          {/* below xl the fragments sit in-flow under the copy instead of floating around it */}
          <div aria-hidden className="relative mt-12 flex w-full justify-center gap-3 xl:hidden">
            <div className="-rotate-6 pt-4">
              <StickyNote className="w-[150px] px-4 pb-4 pt-6 [&_p]:text-[14px]">
                Repay once — on your salary day.
              </StickyNote>
            </div>
            <div className="rotate-3">
              <RepaymentCard className="w-[214px] p-3.5" />
            </div>
            <div className="hidden -rotate-2 pt-6 md:block">
              <AmountCard />
            </div>
          </div>
        </div>
      </InsetPanel>
    </div>
  );
}

/* ------------------------------------------------------------------ solutions */

function Solutions() {
  return (
    <Section labelledBy="why-title">
      <Container>
        <SectionHead
          label="Why DhanBoost"
          id="why-title"
          lead="Lending, reimagined"
          muted="to feel effortless"
          sub="A transparent, fast and hassle-free experience built around you — with no hidden costs and no paperwork."
        />
        <BlueprintGrid cols={3} className="reveal mt-14 sm:mt-16">
          <BlueprintItem icon={<Zap size={22} strokeWidth={1.9} />} title="Lightning-fast application">
            Get an eligibility decision in minutes and funds sent straight to your account after you accept.
          </BlueprintItem>
          <BlueprintItem icon={<IndianRupee size={22} strokeWidth={1.9} />} title="100% transparent pricing">
            Every rupee of interest and charges is shown upfront before you accept. No surprises, no advance fees.
          </BlueprintItem>
          <BlueprintItem icon={<ShieldCheck size={22} strokeWidth={1.9} />} title="Strong data security">
            Your data is protected with strong encryption and strict access controls. We never sell your information.
          </BlueprintItem>
        </BlueprintGrid>

        {/* the product, in an accent frame cropped at the bottom */}
        <div className="relative mt-16 sm:mt-20">
          <FloatTile className="-left-2 -top-7 z-10 hidden sm:block lg:-left-7" rotate={-10} delay={1}>
            <CalendarTile day="30" month="Jun" size={84} />
          </FloatTile>
          <FloatTile className="-right-2 top-[38%] z-10 hidden sm:block lg:-right-7" rotate={8} delay={3}>
            <CheckTile size={76} />
          </FloatTile>
          <div
            role="img"
            aria-label="The DhanBoost borrower dashboard: ₹8,820 net disbursed on a ₹10,000 loan, one repayment of ₹12,700 due on salary day."
            className="lp-crop-mask relative overflow-hidden rounded-[24px] px-3 pt-6 [--s:.285] sm:rounded-[32px] sm:px-8 sm:pt-12 sm:[--s:.52] md:[--s:.6] lg:[--s:.8] xl:[--s:.98]"
            style={{
              background:
                "radial-gradient(90% 70% at 15% 0%, rgb(var(--c-gold-400)) 0%, transparent 60%), linear-gradient(160deg, rgb(var(--c-gold-500)) 0%, rgb(var(--c-gold-600)) 100%)",
              height: "calc(var(--s) * 600px + 48px)",
            }}
          >
            <div
              inert
              className="absolute left-1/2 origin-top"
              style={{ transform: "translateX(-50%) scale(var(--s))", top: "clamp(24px, 4vw, 48px)" }}
            >
              <MiniDashboard />
            </div>
          </div>
        </div>
      </Container>
    </Section>
  );
}

/* ------------------------------------------------------------------ features (bento) */

function Features() {
  return (
    <div className="px-3 sm:px-5">
      <div className="mx-auto max-w-[1400px] rounded-[24px] border border-line bg-surface sm:rounded-[32px]">
        <Section labelledBy="features-title" className="overflow-visible">
          <Container>
            <SectionHead
              label="Features"
              id="features-title"
              lead="Every loan includes"
              muted="the DhanBoost promise"
              sub="Flexible, short-term financing with transparent terms — fully online, from first tap to funds in your account."
            />
            <div className="mt-14 grid gap-4 sm:mt-16 md:grid-cols-2 lg:grid-cols-3">
              <BentoCard className="reveal" title="Minimal documentation" art={<DocsArt />}>
                Just PAN, Aadhaar and bank details — verified digitally. Complete everything from your phone.
              </BentoCard>
              <BentoCard className="reveal d1" title="Instant digital verification" art={<VerifyArt />}>
                Paperless KYC with PAN and Aadhaar, plus a quick eligibility check — all online.
              </BentoCard>
              <BentoCard className="reveal d2" title="Key Fact Statement upfront" art={<KfsArt />}>
                See your exact interest, APR and total repayment in a clear summary. Happy with it? Sign securely in a tap.
              </BentoCard>
              <BentoCard className="reveal" title="Salary-linked, single repayment" art={<SalaryArt />}>
                Repay once, on your salary day. Your exact due date is confirmed before you accept.
              </BentoCard>
              <BentoCard className="reveal d1" title="Clear early, save more" art={<InterestArt />}>
                Repay via UPI, net-banking or auto-debit. No pre-closure or prepayment charges.
              </BentoCard>
              <BentoCard className="reveal d2" dashed title="Real human support" art={<SupportArt hours={BRAND.hours} />}>
                Friendly support over chat, email and phone, six days a week — plus a transparent grievance redressal channel.
              </BentoCard>
            </div>
            <p className="m-0 mt-10 text-center text-[14.5px] text-slate">
              and a lot more, step by step —{" "}
              <ArrowLink href="/how-it-works">see the full process</ArrowLink>
            </p>
          </Container>
        </Section>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ verification ("integrations") */

type Rail = { icon: typeof Zap; label: string };

const RAIL_TOP: Rail[] = [
  { icon: IdCard, label: "PAN check" },
  { icon: Fingerprint, label: "Aadhaar e-KYC" },
  { icon: FolderLock, label: "DigiLocker" },
  { icon: Gauge, label: "Credit bureau" },
  { icon: Landmark, label: "Bank account" },
];
const RAIL_BOTTOM: Rail[] = [
  { icon: PenLine, label: "Aadhaar eSign" },
  { icon: FileText, label: "Key Fact Statement" },
  { icon: Banknote, label: "Bank transfer" },
  { icon: Smartphone, label: "UPI" },
  { icon: Globe, label: "Net-banking" },
  { icon: RefreshCw, label: "Auto-debit" },
];

function RailTile({ icon: Icon, label, ghost }: Rail & { ghost?: boolean }) {
  return (
    <li
      aria-hidden={ghost || undefined}
      className={cn(
        "lp-tile flex h-[104px] w-[104px] shrink-0 flex-col items-center justify-center gap-2.5 rounded-[26px] text-center sm:h-[124px] sm:w-[124px] sm:rounded-[30px]",
        ghost && "opacity-60",
      )}
    >
      <Icon aria-hidden size={28} strokeWidth={1.7} className={ghost ? "text-muted" : "text-ink"} />
      {!ghost && <span className="px-1 text-[11.5px] leading-tight text-slate sm:text-[12px]">{label}</span>}
    </li>
  );
}

function Verification() {
  return (
    <Section labelledBy="verify-title">
      {/* blueprint backdrop */}
      <div aria-hidden className="pointer-events-none absolute inset-0">
        <span className="lp-rule top-[46%]" />
        <span className="lp-rule top-[74%]" />
        <span className="lp-vrule left-1/2" />
        <span className="lp-vrule left-[22%] hidden md:block" />
        <span className="lp-vrule left-[78%] hidden md:block" />
      </div>
      <Container>
        <SectionHead
          label="Verification"
          id="verify-title"
          lead="Everything is verified digitally"
          muted="no paperwork, no branch visits"
          sub="Paperless KYC with PAN and Aadhaar, a securely e-signed agreement, funds straight to your bank — and easy repayment via UPI, net-banking or auto-debit."
        />
        <div className="relative mt-12 flex justify-center">
          <AppIcon size={96} className="relative z-10" />
        </div>
        <div className="relative mt-10 grid gap-4 sm:gap-5">
          <ul className="lp-bleed-mask m-0 hidden list-none justify-center gap-4 p-0 sm:flex sm:gap-5">
            <RailTile icon={Lock} label="" ghost />
            {RAIL_TOP.map((r) => (
              <RailTile key={r.label} {...r} />
            ))}
            <RailTile icon={ShieldCheck} label="" ghost />
          </ul>
          <ul className="lp-bleed-mask m-0 hidden list-none justify-center gap-4 p-0 sm:flex sm:gap-5">
            {RAIL_BOTTOM.map((r) => (
              <RailTile key={r.label} {...r} />
            ))}
          </ul>
          {/* phones: every tile, wrapped */}
          <ul className="m-0 flex list-none flex-wrap justify-center gap-3 p-0 sm:hidden">
            {[...RAIL_TOP, ...RAIL_BOTTOM].map((r) => (
              <RailTile key={r.label} {...r} />
            ))}
          </ul>
        </div>
      </Container>
    </Section>
  );
}

/* ------------------------------------------------------------------ reviews / promise */

function Reviews() {
  return (
    <Section labelledBy="reviews-title">
      <Container>
        <SectionHead
          label="Reviews"
          id="reviews-title"
          lead="Borrowers, in their own words"
          muted="earning trust the honest way"
          sub="We'd rather show you real, verified borrower reviews than invent them. Until they're in, here is what we hold ourselves to."
        />
        <div className="mt-14 gap-5 sm:mt-16 md:columns-2 lg:columns-3 [&>*]:mb-5">
          <div className="relative break-inside-avoid">
            <QuoteCard
              quote="As customers complete their loans, their verified feedback and independent ratings will be published here."
              name="Reviews"
              role="Verified reviews are coming soon"
              avatar={
                <IconTile size={38} className="text-gold-dark">
                  <MessageSquareQuote size={17} />
                </IconTile>
              }
            >
              <ArrowLink href="/reviews" className="mt-4 text-[13.5px]">
                Visit the reviews page
              </ArrowLink>
            </QuoteCard>
            <FloatTile className="-right-3 -top-5" rotate={8} delay={2}>
              <IconTile size={52} className="text-ink">
                <Ellipsis size={22} />
              </IconTile>
            </FloatTile>
          </div>

          <figure className="relative m-0 flex min-h-[380px] break-inside-avoid flex-col justify-between overflow-hidden rounded-[24px] bg-navy p-7 text-white sm:min-h-[440px]">
            <div
              aria-hidden
              className="pointer-events-none absolute -right-20 -top-24 h-72 w-72 rounded-full"
              style={{ background: "radial-gradient(circle, rgb(var(--c-gold-500) / .35), transparent 70%)" }}
            />
            <p className="relative m-0 text-[12.5px] font-semibold uppercase tracking-[0.1em] text-gold-400">Our mission</p>
            <blockquote className="relative m-0 mt-8 text-[clamp(1.4rem,2.2vw,1.75rem)] font-medium leading-[1.25] tracking-[-0.03em] text-white">
              “To make small-ticket credit transparent, fast and humane for every working Indian.”
            </blockquote>
            <figcaption className="relative mt-8 flex items-center gap-3 text-[13.5px]">
              <Avatar name="DhanBoost" size={38} className="border-navy" />
              <span className="leading-tight">
                <span className="block text-white/70">DhanBoost</span>
                <span className="block font-medium text-white">No fine print. No advance fees. No pressure.</span>
              </span>
            </figcaption>
          </figure>

          <QuoteCard
            quote="We show every charge, upfront, in plain language. If we wouldn't accept the terms, we won't offer them."
            name="Our values"
            role="Transparency first"
            avatar={<ValueIcon icon={IndianRupee} />}
          />
          <QuoteCard
            quote="Real human support and respectful collections. We design for your wellbeing, not just conversion."
            name="Our values"
            role="People over funnels"
            avatar={<ValueIcon icon={Users} />}
          />
          <QuoteCard
            quote="Secure data handling, fair terms, and a clear grievance path — built in, not bolted on."
            name="Our values"
            role="Compliance by design"
            avatar={<ValueIcon icon={HeartHandshake} />}
          />
        </div>
      </Container>
    </Section>
  );
}

function ValueIcon({ icon: Icon }: { icon: typeof Zap }) {
  return (
    <IconTile size={38} className="text-gold-dark">
      <Icon size={17} />
    </IconTile>
  );
}

/* ------------------------------------------------------------------ pricing */

function Pricing() {
  return (
    <Section labelledBy="pricing-title" className="overflow-visible">
      <Container>
        <SectionHead
          label="Pricing"
          id="pricing-title"
          lead="Simple, transparent pricing"
          muted="every rupee shown upfront"
          sub="Fees are netted from your disbursal and disclosed in your Key Fact Statement — you never send money to receive a loan."
        />
        <div className="mt-14 grid items-stretch gap-5 sm:mt-16 lg:mt-20 lg:grid-cols-3 lg:gap-6">
          <PlanCard
            kicker="Fees"
            figure="10%"
            unit="of principal"
            caption="A one-time processing fee, plus 18% GST on that fee."
            items={[
              "Deducted from your disbursal — never paid in advance",
              "Shown in your Key Fact Statement before you accept",
              "No pre-closure or prepayment charges",
            ]}
            cta={{ href: "/signup/start", label: "Apply for a Loan" }}
          />
          <PlanCard
            raised
            kicker="Interest · worked example"
            figure="1%"
            unit="per day"
            caption="On principal, only for the days you hold it. Repay early and pay less."
            items={[
              <>
                Borrow <b className="font-semibold">₹10,000</b> — fee ₹1,000 + GST ₹180
              </>,
              <>
                You receive <b className="font-semibold">₹8,820</b> in your bank account
              </>,
              <>
                Repay on day 27: <b className="font-semibold">₹12,700</b> (₹10,000 + ₹2,700 interest)
              </>,
              "One repayment, on your salary day — within 40 days",
            ]}
            cta={{ href: "/calculator", label: "Open the calculator" }}
            extra={
              <FloatTile className="-right-4 -top-6 z-10" rotate={10} delay={1}>
                <IconTile size={60} className="text-gold-dark">
                  <Zap size={26} strokeWidth={2} fill="currentColor" />
                </IconTile>
              </FloatTile>
            }
          />
          <PlanCard
            kicker="Late payment"
            figure="2%"
            unit="per day"
            caption="On the overdue principal, as set out in your Key Fact Statement."
            items={[
              "Capped at 30 days",
              "Can affect your credit score",
              "Struggling? Contact us early — we'll help you find a way forward respectfully",
            ]}
            cta={{ href: "/faq", label: "Read the FAQs" }}
          />
        </div>
        <p className="m-0 mx-auto mt-10 max-w-[640px] text-center text-[12.5px] leading-[1.6] text-slate">
          Illustrative figures. Your exact amount, due date and charges are confirmed in your Key Fact Statement
          before you accept.
        </p>
      </Container>
    </Section>
  );
}

/* ------------------------------------------------------------------ FAQ preview */

const FAQS = [
  {
    q: "How quickly can I get a loan from DhanBoost?",
    a: "The whole journey is fully online. Once your agreement is e-signed and your KYC is complete, your funds are released to your bank account.",
  },
  {
    q: "What documents do I need to apply?",
    a: "Typically just your PAN, Aadhaar (for KYC) and bank account details. Everything is verified digitally — no physical paperwork or branch visits.",
  },
  {
    q: "Are there any hidden charges or advance fees?",
    a: "Never. DhanBoost does not charge any advance or upfront fee. All applicable interest and charges are shown in your loan summary before you accept the offer.",
  },
  {
    q: "Will checking my eligibility affect my credit score?",
    a: "No. Checking your eligibility on DhanBoost does not impact your credit score — a formal credit enquiry only happens if you choose to accept an offer.",
  },
];

function Faq() {
  return (
    <Section labelledBy="faq-title" className="pt-4">
      <Container className="grid gap-10 lg:grid-cols-[minmax(0,0.85fr)_minmax(0,1.15fr)] lg:gap-16">
        <SectionHead
          align="left"
          label="Good to know"
          labelIcon={<CircleHelp aria-hidden size={13} className="text-gold-dark" />}
          id="faq-title"
          lead="Frequently asked"
          muted="questions"
          sub={
            <>
              Everything about applying, rates, repayment and security —{" "}
              <ArrowLink href="/faq" className="text-[15.5px]">
                view all FAQs
              </ArrowLink>
            </>
          }
        />
        <div className="grid gap-3">
          {FAQS.map((f) => (
            <details key={f.q} className="lp-qa lp-card group rounded-[20px] px-5 py-1 sm:px-6">
              <summary className="flex items-center justify-between gap-4 rounded-[14px] py-4 text-[15px] font-medium tracking-[-0.015em] text-ink focus-visible:outline focus-visible:outline-2 focus-visible:outline-gold-dark">
                {f.q}
                <span className="lp-qa-icon grid h-8 w-8 shrink-0 place-items-center rounded-full bg-grey-100 text-ink transition-transform duration-300">
                  <Plus aria-hidden size={16} />
                </span>
              </summary>
              <p className="m-0 pb-5 pr-10 text-[14.5px] leading-[1.6] text-slate">{f.a}</p>
            </details>
          ))}
        </div>
      </Container>
    </Section>
  );
}
