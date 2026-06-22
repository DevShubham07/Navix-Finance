import Link from "next/link";
import {
  Check,
  CreditCard,
  Zap,
  ClipboardCheck,
  Lock,
  IndianRupee,
  ShieldCheck,
} from "lucide-react";
import { SectionHead } from "@/components/site";
import { EmiCalculator } from "@/components/site/emi-calculator";
import { BRAND, LENDING_PARTNERS } from "@/lib/brand";

const TRUST = [
  { title: "100% digital application", sub: "Apply online in minutes, from anywhere." },
  { title: "Minimal documentation", sub: "PAN, Aadhaar & bank details — verified digitally." },
  { title: "Direct bank disbursal", sub: "Funds sent to your account by the partner NBFC." },
  { title: "Zero advance fees", sub: "No upfront charges to apply — ever." },
];

const STATS = [
  { num: <>₹{BRAND.maxLoanLakh}<span className="unit">L</span></>, label: "Loans up to" },
  { num: <>24–48<span className="unit">hr</span></>, label: "Disbursal window" },
  { num: <>100<span className="unit">%</span></>, label: "Paperless process" },
  { num: <>₹0</>, label: "Hidden charges" },
];

const STEPS = [
  { n: 1, h: "Apply Online", p: "Share your mobile, PAN, salary and employment details through our secure platform." },
  { n: 2, h: "Verification & eKYC", p: "Aadhaar via DigiLocker, PAN check and bank verification — all paperless." },
  { n: 3, h: "e-Sign Agreement", p: "Review the sanction letter & Key Fact Statement from the lending partner, then e-sign." },
  { n: 4, h: "Direct Bank Disbursal", p: "The partner NBFC disburses the amount straight to your verified bank account." },
];

const FEATURES = [
  { Icon: ClipboardCheck, h: "Smart Eligibility Screening", p: "Clear criteria shown upfront, so you know where you stand before you apply." },
  { Icon: Lock, h: "Secure by Design", p: "256-bit encryption and consent-based data use protect your information end to end." },
  { Icon: IndianRupee, h: "Transparent Pricing", p: "Every charge is disclosed before you sign — no hidden fees, no surprises." },
  { Icon: ShieldCheck, h: "Responsible Lending", p: "Our partner NBFCs follow RBI fair-practice norms and report responsibly to bureaus." },
];

export default function HomePage() {
  return (
    <>
      {/* Hero */}
      <section className="hero">
        <div className="container">
          <div className="hero-copy">
            <span className="eyebrow">Digital Lending Platform</span>
            <h1>
              Instant personal loans.
              <br />
              Fully digital.
              <br />
              <span className="accent">Fairly priced.</span>
            </h1>
            <p className="hero-sub">
              A paperless application, disbursal in 24–48 hours, and transparent terms — powered
              by our RBI-registered NBFC lending partners.
            </p>
            <div className="hero-cta">
              <Link href="/signup/pan" className="btn btn-gold">
                Apply for a Loan
              </Link>
              <Link href="#how-it-works" className="btn btn-outline">
                How It Works
              </Link>
            </div>
            <div className="hero-meta">
              <span className="rbi-badge">
                <span className="dot" /> Loans by RBI-registered NBFC partners
              </span>
            </div>
          </div>
          <div className="trust-card reveal">
            <div className="trust-card-head">
              <h3>What you can expect</h3>
              <span className="pill">Platform</span>
            </div>
            <ul className="trust-list">
              {TRUST.map((t) => (
                <li key={t.title}>
                  <span className="tick">
                    <Check strokeWidth={3} />
                  </span>
                  <span>
                    <b>{t.title}</b>
                    <span>{t.sub}</span>
                  </span>
                </li>
              ))}
            </ul>
          </div>
        </div>
      </section>

      {/* Capability strip */}
      <section className="stats">
        <div className="container">
          {STATS.map((s, i) => (
            <div className="stat" key={i}>
              <div className="stat-num">{s.num}</div>
              <div className="stat-label">{s.label}</div>
            </div>
          ))}
        </div>
      </section>

      {/* Product overview */}
      <section className="section">
        <div className="container">
          <SectionHead center eyebrow="Loan Products" title="Two clear ways to borrow">
            Whichever you choose, your loan is sanctioned and disbursed by an RBI-registered NBFC
            lending partner, with all terms shown before you sign.
          </SectionHead>
          <div className="grid grid-2">
            <article className="card product-card reveal" id="personal">
              <div className="pc-head">
                <div>
                  <h3>Personal Loan</h3>
                  <p className="mb-0">A flexible loan for planned expenses, repaid over a tenure that suits you.</p>
                </div>
                <span className="pc-icon">
                  <CreditCard />
                </span>
              </div>
              <div className="pc-body">
                <ul className="feature-list">
                  <li>Larger amounts with <strong>flexible tenures</strong></li>
                  <li>Fixed, <strong>transparent monthly EMIs</strong></li>
                  <li>Rates and fees set by the <strong>partner NBFC</strong></li>
                </ul>
              </div>
              <div className="pc-foot">
                <Link href="/products#personal" className="arrow-link">Learn more</Link>
              </div>
            </article>
            <article className="card product-card reveal" id="instant">
              <div className="pc-head">
                <div>
                  <h3>Instant Loan</h3>
                  <p className="mb-0">A smaller, short-term advance for urgent needs — approved and disbursed fast.</p>
                </div>
                <span className="pc-icon">
                  <Zap />
                </span>
              </div>
              <div className="pc-body">
                <ul className="feature-list">
                  <li><strong>Quick approval</strong> for eligible applicants</li>
                  <li><strong>Short tenures</strong> for near-term cash needs</li>
                  <li>Disbursed <strong>directly to your bank</strong> by the partner NBFC</li>
                </ul>
              </div>
              <div className="pc-foot">
                <Link href="/products#instant" className="arrow-link">Learn more</Link>
              </div>
            </article>
          </div>
        </div>
      </section>

      {/* EMI Calculator */}
      <section className="section section--grey" id="calculator">
        <div className="container">
          <SectionHead center eyebrow="EMI Calculator" title="Estimate your monthly EMI">
            Move the sliders to see an indicative monthly instalment, total interest and total payable.
          </SectionHead>
          <div className="reveal">
            <EmiCalculator />
          </div>
        </div>
      </section>

      {/* How it works */}
      <section className="section" id="how-it-works">
        <div className="container">
          <SectionHead center eyebrow="How It Works" title="From application to your bank account">
            A guided, fully online journey. Eligible applicants are typically funded within one to
            two business days.
          </SectionHead>
          <div className="steps steps-rail">
            {STEPS.map((s) => (
              <div className="step reveal" key={s.n}>
                <div className="step-num">{s.n}</div>
                <h4>{s.h}</h4>
                <p>{s.p}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Feature showcase */}
      <section className="section section--grey">
        <div className="container">
          <SectionHead center eyebrow="Built for Digital Lending" title="Depth where it matters" />
          <div className="grid grid-4">
            {FEATURES.map(({ Icon, h, p }) => (
              <div className="feature-col reveal" key={h}>
                <div className="fc-icon">
                  <Icon />
                </div>
                <h3>{h}</h3>
                <p>{p}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Partner spotlight */}
      <section className="section">
        <div className="container">
          <SectionHead center eyebrow="Our Lending Partners" title="Loans by RBI-registered NBFCs">
            NAVIX is a technology platform, not a lender. Every loan is sanctioned and disbursed by
            one of our RBI-registered NBFC lending partners, who remain the lender of record.
          </SectionHead>
          <div className="grid grid-2 mb-3">
            {LENDING_PARTNERS.map((p) => (
              <article className="card partner-card reveal" key={p.name}>
                <div className="p-logo">
                  <div className="img-slot img-slot--ratio">
                    <span className="slot-label">NBFC logo</span>
                  </div>
                </div>
                <div>
                  <span className="pill">Lending Partner</span>
                  <h3 style={{ margin: ".6rem 0 .3rem" }}>{p.name}</h3>
                  <p className="note-inline mb-1">
                    RBI CoR No.: <strong>{p.corNo}</strong>
                  </p>
                  <p className="note-inline mb-2">{p.blurb}</p>
                  <Link href="/partners" className="arrow-link">View disclosures</Link>
                </div>
              </article>
            ))}
          </div>
        </div>
      </section>

      {/* CTA band */}
      <section className="section section--navy">
        <div className="container text-center">
          <div className="section-head center" style={{ marginBottom: "1.8rem" }}>
            <h2>Ready when you are</h2>
            <p style={{ color: "#BFD0E6" }}>Start a paperless application — it only takes a few minutes.</p>
          </div>
          <div className="hero-cta" style={{ justifyContent: "center" }}>
            <Link href="/signup/pan" className="btn btn-gold">
              Apply for a Loan
            </Link>
            <Link href="#calculator" className="btn btn-outline-light">
              Open the EMI Calculator
            </Link>
          </div>
        </div>
      </section>
    </>
  );
}
