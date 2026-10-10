import Image from "next/image";
import Link from "next/link";
import {
  ArrowRight,
  Check,
  ChevronsRight,
  Clock,
  Flag,
  Hourglass,
  Lightbulb,
  Mail,
  MapPin,
  MessageCircle,
  Phone,
  Timer,
} from "lucide-react";
import { BRAND } from "@/lib/brand";
import { cn } from "@/lib/utils";
import { CalendarTile, FloatTile, IconTile, InsetPanel } from "./landing/primitives";

/**
 * Public marketing footer — the landing layout language: a dotted inset panel with the brand
 * headline and link columns, a scattered field of tilted icon tiles, then the legal small print
 * (legal entity, CIN, disclaimer) and the © row. All legal text is carried over verbatim.
 */

const COLUMNS = [
  {
    title: "Company",
    links: [
      { href: "/about", label: "About Us" },
      { href: "/products", label: "Loan Products" },
      { href: "/careers", label: "Careers" },
      { href: "/blog", label: "Resources" },
    ],
  },
  {
    title: "Policies",
    links: [
      { href: "/privacy", label: "Privacy Policy" },
      { href: "/terms", label: "Terms & Conditions" },
      { href: "/fair-practices", label: "Fair Practices Code" },
      { href: "/grievance", label: "Grievance Redressal" },
      { href: "/faq", label: "FAQs" },
    ],
  },
];

const FOCUS = "focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-gold-dark";

/** The scattered tile field (decorative). Tiles marked `phone` stay visible on small screens. */
const FIELD: { left: string; top: string; rotate: number; node: React.ReactNode; phone?: boolean }[] = [
  { left: "3%", top: "30%", rotate: -12, node: <CalendarTile day="30" month="Jun" size={70} />, phone: true },
  { left: "14%", top: "6%", rotate: 9, node: <MessageCircle size={26} strokeWidth={1.8} /> },
  { left: "25%", top: "50%", rotate: -6, node: <Clock size={26} strokeWidth={1.8} />, phone: true },
  { left: "36%", top: "14%", rotate: 12, node: <Flag size={24} strokeWidth={1.8} /> },
  { left: "47%", top: "46%", rotate: -9, node: <Check size={28} strokeWidth={2.4} />, phone: true },
  { left: "58%", top: "4%", rotate: 7, node: <Hourglass size={24} strokeWidth={1.8} /> },
  { left: "68%", top: "44%", rotate: -14, node: <Lightbulb size={26} strokeWidth={1.8} />, phone: true },
  { left: "80%", top: "10%", rotate: 10, node: <Timer size={26} strokeWidth={1.8} /> },
  { left: "88%", top: "46%", rotate: -5, node: <ChevronsRight size={28} strokeWidth={1.8} />, phone: true },
];

export function MarketingFooter() {
  return (
    <footer className="lp px-3 pb-6 pt-4 sm:px-5">
      <InsetPanel className="mx-auto max-w-[1400px]">
        <div className="mx-auto grid max-w-[1240px] gap-12 px-6 pb-6 pt-12 sm:px-10 sm:pt-16 lg:grid-cols-[minmax(0,1.25fr)_minmax(0,1fr)] lg:gap-16 lg:px-14 lg:pt-20">
          <div>
            <Link href="/" aria-label="DhanBoost home" className={cn("inline-flex items-center gap-2.5 rounded-xl text-ink hover:text-ink", FOCUS)}>
              <Image src="/navix-mark.png" alt="" width={40} height={40} className="h-10 w-10 rounded-[11px]" />
              <span className="text-[19px] font-semibold tracking-[-0.03em]">DhanBoost</span>
            </Link>
            <h2 className="lp-h2 mt-8 max-w-[14ch] text-[clamp(2rem,4.4vw,3.4rem)]">
              <span className="block">Small-ticket credit,</span>
              <span className="block text-muted">transparent, fast and humane.</span>
            </h2>
            <p className="m-0 mt-6 max-w-[440px] text-[14.5px] leading-[1.65] text-slate">
              A premium digital lending platform offering fast, fully-online, fairly-priced personal loans: salary-linked,
              with a single repayment and no advance fees.
            </p>
            <div className="mt-8 flex flex-wrap gap-3">
              <Link
                href="/signup/start"
                className={cn(
                  "group inline-flex h-11 items-center gap-2 rounded-full bg-gold px-5 text-[14px] font-semibold text-ink shadow-gold transition-[transform,background-color] duration-300 hover:-translate-y-px hover:bg-gold-400 hover:text-ink",
                  FOCUS,
                )}
              >
                Apply Now <ArrowRight aria-hidden size={16} className="transition-transform group-hover:translate-x-0.5" />
              </Link>
              <Link
                href="/contact"
                className={cn(
                  "inline-flex h-11 items-center rounded-full border border-[rgb(var(--c-line-2))] bg-paper px-5 text-[14px] font-semibold text-ink shadow-pill transition-transform duration-300 hover:-translate-y-px hover:text-ink",
                  FOCUS,
                )}
              >
                Get in touch
              </Link>
            </div>
          </div>

          <div className="grid gap-10 sm:grid-cols-2 lg:grid-cols-[1fr_1.15fr]">
            {COLUMNS.map((col) => (
              <nav key={col.title} aria-label={col.title}>
                <h3 className="m-0 text-[13px] font-semibold uppercase tracking-[0.08em] text-slate">{col.title}</h3>
                <ul className="m-0 mt-4 grid list-none gap-1 p-0">
                  {col.links.map((l) => (
                    <li key={l.href}>
                      <Link
                        href={l.href}
                        className={cn("group flex items-center justify-between gap-3 rounded-lg py-1.5 text-[15px] font-medium tracking-[-0.01em] text-ink hover:text-gold-dark", FOCUS)}
                      >
                        {l.label}
                        <ArrowRight aria-hidden size={15} className="text-muted transition-transform duration-300 group-hover:translate-x-0.5 group-hover:text-gold-dark" />
                      </Link>
                    </li>
                  ))}
                </ul>
              </nav>
            ))}
            <address className="not-italic sm:col-span-2">
              <h3 className="m-0 text-[13px] font-semibold uppercase tracking-[0.08em] text-slate">Get in touch</h3>
              <ul className="m-0 mt-4 grid list-none gap-3 p-0 text-[14px] leading-[1.55] text-slate">
                <li className="flex gap-3">
                  <MapPin aria-hidden size={17} className="mt-0.5 shrink-0 text-gold-dark" />
                  <span>
                    Plot No 268, 1st Floor, Sector 33, Subhash Chowk, Islampur, Gurgaon, Haryana – 122001
                  </span>
                </li>
                <li className="flex gap-3">
                  <Phone aria-hidden size={17} className="mt-0.5 shrink-0 text-gold-dark" />
                  <a href={BRAND.phoneHref} className={cn("rounded text-slate hover:text-ink", FOCUS)}>
                    {BRAND.phone}
                  </a>
                </li>
                <li className="flex gap-3">
                  <Mail aria-hidden size={17} className="mt-0.5 shrink-0 text-gold-dark" />
                  <a href={`mailto:${BRAND.email}`} className={cn("rounded text-slate hover:text-ink", FOCUS)}>
                    {BRAND.email}
                  </a>
                </li>
              </ul>
            </address>
          </div>
        </div>

        {/* scattered icon tiles */}
        <div aria-hidden className="relative h-[150px] sm:h-[200px] lg:h-[230px]">
          {FIELD.map((f, i) => (
            <FloatTile
              key={i}
              className={f.phone ? undefined : "hidden md:block"}
              style={{ left: f.left, top: f.top }}
              rotate={f.rotate}
              delay={i % 5}
            >
              <div className="origin-top-left scale-[.7] sm:scale-100">
                {i === 0 ? f.node : <IconTile size={64}>{f.node}</IconTile>}
              </div>
            </FloatTile>
          ))}
        </div>
      </InsetPanel>

      <div className="mx-auto max-w-[1400px] px-3 sm:px-6">
        <div className="grid gap-4 py-8 text-[12.5px] leading-[1.7] text-slate lg:grid-cols-[minmax(0,1fr)_minmax(0,2.4fr)] lg:gap-12">
          <p className="m-0">
            <b className="font-semibold text-ink">NAVIX Finance Private Limited</b>
            <br />
            A digital lending platform.
            <br />
            CIN: <b className="font-semibold text-ink">U64990HR2026PTC144926</b>
          </p>
          <p className="m-0">
            <b className="font-semibold text-ink">Disclaimer:</b> DhanBoost (NAVIX Finance Private Limited) operates a digital
            lending platform offering salary-linked personal loans. Loan approval is subject to DhanBoost&apos;s credit policy
            and eligibility assessment. DhanBoost does not charge any advance fee for loan processing. Representative APR and
            all charges are disclosed before you accept any offer. Please borrow responsibly.
          </p>
        </div>
        <div className="flex flex-col gap-4 border-t border-line py-6 text-[13px] text-slate sm:flex-row sm:items-center sm:justify-between">
          <span>
            © 2026 NAVIX Finance Private Limited. All rights reserved. · Built by{" "}
            <a
              href="https://softsolutionsai.com"
              target="_blank"
              rel="noopener noreferrer"
              className={cn("rounded text-slate underline-offset-4 hover:text-ink hover:underline", FOCUS)}
            >
              softsolutionsai.com
            </a>
          </span>
          <span className="flex items-center gap-5">
            <Link href="/privacy" className={cn("rounded text-slate hover:text-ink", FOCUS)}>
              Privacy
            </Link>
            <Link href="/terms" className={cn("rounded text-slate hover:text-ink", FOCUS)}>
              Terms
            </Link>
            <Link href="/grievance" className={cn("rounded text-slate hover:text-ink", FOCUS)}>
              Grievance
            </Link>
            <a
              href="https://www.linkedin.com/company/softsolutionsai/"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="SoftSolutionsAI on LinkedIn"
              className={cn("inline-flex items-center rounded text-slate hover:text-ink", FOCUS)}
            >
              <svg width="17" height="17" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                <path d="M4.98 3.5A2.5 2.5 0 1 1 0 3.5a2.5 2.5 0 0 1 4.98 0zM.5 8h4V24h-4V8zM8 8h3.8v2.2h.05c.53-1 1.83-2.2 3.77-2.2 4.03 0 4.78 2.65 4.78 6.1V24h-4v-6.9c0-1.65-.03-3.77-2.3-3.77-2.3 0-2.65 1.8-2.65 3.65V24H8V8z" />
              </svg>
            </a>
          </span>
        </div>
      </div>
    </footer>
  );
}
