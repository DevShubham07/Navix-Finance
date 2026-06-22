"use client";

import * as React from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { Brand } from "./brand";

type NavLink = { label: string; href: string };
type NavItem =
  | { label: string; href: string }
  | { label: string; dropdown: Array<NavLink & { sub?: string }> };

const NAV: NavItem[] = [
  { label: "Home", href: "/" },
  { label: "About Us", href: "/about" },
  {
    label: "Loan Products",
    dropdown: [
      { label: "Personal Loan", href: "/products#personal", sub: "Plan ahead with flexible tenures" },
      { label: "Instant Loan", href: "/products#instant", sub: "For urgent, short-term needs" },
    ],
  },
  { label: "Lending Partners", href: "/partners" },
  {
    label: "Policies",
    dropdown: [
      { label: "Privacy Policy", href: "/policies#privacy" },
      { label: "Terms & Conditions", href: "/policies#terms" },
      { label: "Grievance Redressal Policy", href: "/policies#grievance-policy" },
      { label: "Fair Lending Commitment", href: "/policies#fair-lending" },
    ],
  },
  { label: "Grievance", href: "/grievance" },
  { label: "Contact", href: "/contact" },
];

function Caret() {
  return (
    <svg className="caret" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.5}>
      <path d="M6 9l6 6 6-6" />
    </svg>
  );
}

export function SiteHeader() {
  const pathname = usePathname();
  const [navOpen, setNavOpen] = React.useState(false);
  const [openDropdown, setOpenDropdown] = React.useState<string | null>(null);

  React.useEffect(() => {
    document.body.classList.toggle("nav-open", navOpen);
    return () => document.body.classList.remove("nav-open");
  }, [navOpen]);

  const isActive = (href: string) => (href === "/" ? pathname === "/" : pathname.startsWith(href));

  return (
    <header className="site-header">
      <div className="container">
        <nav className="nav" aria-label="Primary">
          <Brand />
          <ul className="nav-menu">
            {NAV.map((item) => {
              if ("dropdown" in item) {
                const open = openDropdown === item.label;
                return (
                  <li key={item.label} className={`has-dropdown${open ? " open" : ""}`}>
                    <button
                      className="nav-toggle-link"
                      aria-expanded={open}
                      onClick={() => setOpenDropdown(open ? null : item.label)}
                    >
                      {item.label} <Caret />
                    </button>
                    <ul className="dropdown">
                      {item.dropdown.map((d) => (
                        <li key={d.href}>
                          <Link href={d.href} onClick={() => setNavOpen(false)}>
                            {d.label}
                            {d.sub ? <small>{d.sub}</small> : null}
                          </Link>
                        </li>
                      ))}
                    </ul>
                  </li>
                );
              }
              return (
                <li key={item.href} className={isActive(item.href) ? "active" : undefined}>
                  <Link href={item.href} onClick={() => setNavOpen(false)}>
                    {item.label}
                  </Link>
                </li>
              );
            })}
            <li className="nav-apply-mobile">
              <Link className="btn btn-gold" href="/signup/pan" onClick={() => setNavOpen(false)}>
                Apply Now
              </Link>
            </li>
          </ul>
          <div className="nav-cta">
            <Link href="/signup/pan" className="btn btn-gold btn-sm btn-nav-apply">
              Apply Now
            </Link>
            <Link href="/login" className="btn btn-outline btn-sm btn-nav-apply">
              Sign In
            </Link>
            <button
              className="nav-burger"
              aria-label="Toggle menu"
              aria-expanded={navOpen}
              onClick={() => setNavOpen((v) => !v)}
            >
              <span />
            </button>
          </div>
        </nav>
      </div>
    </header>
  );
}
