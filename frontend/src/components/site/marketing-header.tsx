"use client";

import * as React from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { BrandMark } from "./brand-mark";

const ArrowR = () => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
    <path d="M5 12h14M12 5l7 7-7 7" />
  </svg>
);

type DropItem = { href: string; title: string; sub?: string; icon: React.ReactNode };

const PRODUCT_DROP: DropItem[] = [
  {
    href: "/products",
    title: "Instant Personal Loan",
    sub: "₹5,000 – ₹10,00,000",
    icon: (
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <circle cx="12" cy="12" r="9" />
        <path d="M12 7v10M9 9.5h4.5a1.5 1.5 0 0 1 0 3H10a1.5 1.5 0 0 0 0 3H15" />
      </svg>
    ),
  },
  {
    href: "/calculator",
    title: "Calculator & Rates",
    sub: "Plan repayment",
    icon: (
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <rect x="4" y="2" width="16" height="20" rx="2" />
        <path d="M8 6h8M8 10h8M8 14h2M8 18h2" />
      </svg>
    ),
  },
];

const SUPPORT_DROP: DropItem[] = [
  {
    href: "/help",
    title: "Help Center",
    icon: (
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
        <path d="M13.7 21a2 2 0 0 1-3.4 0" />
      </svg>
    ),
  },
  {
    href: "/faq",
    title: "FAQs",
    icon: (
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <circle cx="12" cy="12" r="10" />
        <path d="M9.1 9a3 3 0 0 1 5.8 1c0 2-3 3-3 3" />
        <path d="M12 17h.01" />
      </svg>
    ),
  },
  {
    href: "/contact",
    title: "Contact Us",
    icon: (
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
      </svg>
    ),
  },
  {
    href: "/grievance",
    title: "Grievance Redressal",
    icon: (
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z" />
        <path d="M12 8v4M12 16h.01" />
      </svg>
    ),
  },
];

const DRAWER_LINKS = [
  { href: "/", label: "Home" },
  { href: "/how-it-works", label: "How It Works" },
  { href: "/products", label: "Loan Products" },
  { href: "/calculator", label: "Calculator & Rates" },
  { href: "/about", label: "About Us" },
  { href: "/blog", label: "Resources" },
  { href: "/help", label: "Support" },
  { href: "/contact", label: "Contact" },
];

export function MarketingHeader() {
  const pathname = usePathname();
  const [scrolled, setScrolled] = React.useState(false);
  const [drawerOpen, setDrawerOpen] = React.useState(false);

  React.useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 20);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  // Close the drawer whenever the route changes.
  React.useEffect(() => {
    setDrawerOpen(false);
  }, [pathname]);

  const isActive = (href: string) => (href === "/" ? pathname === "/" : pathname.startsWith(href));
  const close = () => setDrawerOpen(false);

  return (
    <>
      <header className={`header${scrolled ? " scrolled" : ""}`} id="header">
        <div className="wrap">
          <nav className="nav">
            <Link href="/" className="brand">
              <BrandMark />
              <span className="brand-txt">
                <b>NAVIX</b>
                <small>Lending Platform</small>
              </span>
            </Link>

            <div className="menu">
              <Link href="/" className={isActive("/") ? "active" : undefined}>
                Home
              </Link>
              <Link href="/how-it-works" className={isActive("/how-it-works") ? "active" : undefined}>
                How It Works
              </Link>
              <div className="has-drop">
                <Link href="/products" className={isActive("/products") ? "active" : undefined}>
                  Loan Products
                </Link>
                <div className="drop">
                  {PRODUCT_DROP.map((d) => (
                    <Link key={d.title} href={d.href}>
                      <span className="dico">{d.icon}</span>
                      <span>
                        <b>{d.title}</b>
                        {d.sub ? (
                          <>
                            <br />
                            <small style={{ color: "var(--muted)" }}>{d.sub}</small>
                          </>
                        ) : null}
                      </span>
                    </Link>
                  ))}
                </div>
              </div>
              <Link href="/calculator" className={isActive("/calculator") ? "active" : undefined}>
                Calculator
              </Link>
              <Link href="/about" className={isActive("/about") ? "active" : undefined}>
                About
              </Link>
              <div className="has-drop">
                <Link href="/help" className={isActive("/help") ? "active" : undefined}>
                  Support
                </Link>
                <div className="drop">
                  {SUPPORT_DROP.map((d) => (
                    <Link key={d.title} href={d.href}>
                      <span className="dico">{d.icon}</span>
                      <span>
                        <b>{d.title}</b>
                      </span>
                    </Link>
                  ))}
                </div>
              </div>
            </div>

            <div className="nav-cta">
              <Link href="/signup/mobile-otp" className="btn btn-gold btn-sm">
                Apply Now <ArrowR />
              </Link>
              <Link href="/login" className="btn btn-ghost btn-sm">
                Sign In
              </Link>
            </div>

            <button
              className={`hamburger${drawerOpen ? " open" : ""}`}
              id="hamb"
              aria-label="Menu"
              aria-expanded={drawerOpen}
              onClick={() => setDrawerOpen((v) => !v)}
            >
              <span />
              <span />
              <span />
            </button>
          </nav>
        </div>
      </header>

      <aside className={`drawer${drawerOpen ? " open" : ""}`} id="drawer">
        <div className="drawer-top">
          <span className="brand-txt">
            <b style={{ fontSize: "1.3rem", color: "var(--navy-800)" }}>NAVIX</b>
          </span>
          <button className="drawer-close" aria-label="Close menu" onClick={close}>
            ✕
          </button>
        </div>
        <nav>
          {DRAWER_LINKS.map((l) => (
            <Link key={l.href} href={l.href} onClick={close}>
              {l.label}
            </Link>
          ))}
        </nav>
        <div className="drawer-cta">
          <Link href="/signup/mobile-otp" className="btn btn-gold btn-block" onClick={close}>
            Apply Now
          </Link>
          <Link href="/login" className="btn btn-ghost btn-block" onClick={close}>
            Sign In
          </Link>
        </div>
      </aside>
      <div className={`scrim${drawerOpen ? " open" : ""}`} id="scrim" onClick={close} />
    </>
  );
}
