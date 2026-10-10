import * as React from "react";
import Link from "next/link";
import { ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";
import type { TocEntry } from "./legacy-html";
import { ButtonLink, Container, Headline, InsetPanel, SectionLabel } from "./primitives";

/**
 * Sub-page building blocks in the landing layout language: the centred hero inside a dotted inset
 * panel (with optional floating fragments), a dark CTA band, and the long-form document layout
 * (sticky table of contents + comfortable measure) used by the legal pages and blog posts.
 */

export function Breadcrumb({ trail }: { trail: { href?: string; label: string }[] }) {
  return (
    <nav aria-label="Breadcrumb">
      <ol className="m-0 flex list-none flex-wrap items-center justify-center gap-1 p-0 text-[12.5px] text-slate">
        <li>
          <Link href="/" className="rounded text-slate hover:text-ink focus-visible:outline focus-visible:outline-2 focus-visible:outline-gold-dark">
            Home
          </Link>
        </li>
        {trail.map((t, i) => (
          <li key={t.label} className="flex items-center gap-1">
            <ChevronRight aria-hidden size={13} className="text-muted" />
            {t.href && i < trail.length - 1 ? (
              <Link href={t.href} className="rounded text-slate hover:text-ink focus-visible:outline focus-visible:outline-2 focus-visible:outline-gold-dark">
                {t.label}
              </Link>
            ) : (
              <span aria-current="page" className="text-ink">
                {t.label}
              </span>
            )}
          </li>
        ))}
      </ol>
    </nav>
  );
}

/**
 * Centred hero in a dotted inset panel. `floats` are absolutely positioned decorative fragments,
 * shown from `xl` up only (they would crowd the copy on smaller screens).
 */
export function PageHero({
  trail,
  label,
  lead,
  muted,
  sub,
  floats,
  children,
  className,
}: {
  trail: { href?: string; label: string }[];
  label?: React.ReactNode;
  lead: React.ReactNode;
  muted?: React.ReactNode;
  sub?: React.ReactNode;
  floats?: React.ReactNode;
  children?: React.ReactNode;
  className?: string;
}) {
  return (
    <div className="px-3 pt-3 sm:px-5 sm:pt-4">
      <InsetPanel className={cn("mx-auto max-w-[1400px]", className)}>
        {floats && (
          <div aria-hidden className="pointer-events-none absolute inset-0 hidden xl:block">
            {floats}
          </div>
        )}
        <div className="relative mx-auto flex max-w-[820px] flex-col items-center px-5 pb-14 pt-10 text-center sm:pb-20 sm:pt-14 xl:pb-24 xl:pt-16">
          <Breadcrumb trail={trail} />
          {label && (
            <div className="lp-enter mt-8">
              <SectionLabel>{label}</SectionLabel>
            </div>
          )}
          <Headline as="h1" size="h1" lead={lead} muted={muted} className={cn("lp-h1--page lp-enter [--lp-e:.06s]", label ? "mt-6" : "mt-8")} />
          {sub && (
            <p className="lp-enter m-0 mt-6 max-w-[600px] text-[16px] leading-[1.6] text-slate [--lp-e:.12s] sm:text-[17px]">{sub}</p>
          )}
          {children && <div className="lp-enter mt-8 w-full [--lp-e:.18s]">{children}</div>}
        </div>
      </InsetPanel>
    </div>
  );
}

/** Dark closing band: headline, one line, two buttons. */
export function CtaBand({
  id,
  lead,
  muted,
  sub,
  primary,
  secondary,
}: {
  id: string;
  lead: React.ReactNode;
  muted?: React.ReactNode;
  sub: React.ReactNode;
  primary: { href: string; label: string };
  secondary?: { href: string; label: string };
}) {
  return (
    <section aria-labelledby={id} className="px-3 pb-[clamp(56px,7vw,96px)] sm:px-5">
      <div className="relative mx-auto max-w-[1400px] overflow-hidden rounded-[24px] bg-navy px-6 py-14 text-center sm:rounded-[32px] sm:py-20">
        <div
          aria-hidden
          className="pointer-events-none absolute inset-0"
          style={{
            background:
              "radial-gradient(50% 80% at 50% 120%, rgb(var(--c-gold-500) / .35), transparent 70%), radial-gradient(rgb(255 255 255 / .07) 1px, transparent 1.3px) 0 0 / 16px 16px",
          }}
        />
        <div className="relative mx-auto max-w-[680px]">
          <h2 id={id} className="lp-h2 !text-white">
            <span className="block">{lead}</span>
            {muted && <span className="block text-white/60">{muted}</span>}
          </h2>
          <p className="m-0 mx-auto mt-5 max-w-[520px] text-[15.5px] leading-[1.6] text-white/80">{sub}</p>
          <div className="mt-8 flex flex-wrap justify-center gap-3">
            <ButtonLink href={primary.href} size="lg" arrow>
              {primary.label}
            </ButtonLink>
            {secondary && (
              <ButtonLink href={secondary.href} size="lg" tone="white">
                {secondary.label}
              </ButtonLink>
            )}
          </div>
        </div>
      </div>
    </section>
  );
}

/**
 * Long-form document: sticky table of contents on desktop (a collapsible list on phones) beside the
 * body, which is the page's existing HTML rendered verbatim inside `.lp-prose`.
 */
export function DocumentLayout({
  toc,
  body,
  aside,
  after,
}: {
  toc: TocEntry[];
  body: string;
  aside?: React.ReactNode;
  after?: React.ReactNode;
}) {
  return (
    <Container className="py-[clamp(48px,7vw,96px)]">
      <div className={cn("grid gap-10", toc.length > 1 && "lg:grid-cols-[240px_minmax(0,1fr)] lg:gap-16")}>
        {toc.length > 1 && (
          <aside className="lg:sticky lg:top-[100px] lg:self-start">
            <details className="lp-qa lp-card rounded-[18px] px-5 py-1 lg:hidden">
              <summary className="flex items-center justify-between py-3.5 text-[14px] font-medium text-ink focus-visible:outline focus-visible:outline-2 focus-visible:outline-gold-dark">
                On this page
                <ChevronRight aria-hidden size={16} className="lp-qa-icon rotate-45 transition-transform" />
              </summary>
              <TocList toc={toc} />
            </details>
            <nav aria-label="On this page" className="hidden lg:block">
              <p className="m-0 mb-3 text-[12px] font-semibold uppercase tracking-[0.08em] text-slate">On this page</p>
              <TocList toc={toc} />
              {aside}
            </nav>
          </aside>
        )}
        <article className="min-w-0 max-w-[720px]">
          <div className="lp-prose" dangerouslySetInnerHTML={{ __html: body }} />
          {after}
        </article>
      </div>
    </Container>
  );
}

function TocList({ toc }: { toc: TocEntry[] }) {
  return (
    <ol className="m-0 grid list-none gap-0.5 border-l border-line p-0 pb-3 lg:pb-0">
      {toc.map((t) => (
        <li key={t.id}>
          <a
            href={`#${t.id}`}
            className="-ml-px block border-l-2 border-transparent py-1.5 pl-4 text-[13.5px] leading-snug text-slate hover:border-gold hover:text-ink focus-visible:outline focus-visible:outline-2 focus-visible:outline-gold-dark"
          >
            {t.label}
          </a>
        </li>
      ))}
    </ol>
  );
}
