import Link from "next/link";
import { MapPin, Phone, Mail, Linkedin } from "lucide-react";
import { Brand } from "./brand";
import { BRAND } from "@/lib/brand";

const QUICK_LINKS = [
  { label: "About Us", href: "/about" },
  { label: "Loan Products", href: "/products" },
  { label: "Lending Partners", href: "/partners" },
  { label: "Apply Now", href: "/signup" },
  { label: "Contact Us", href: "/contact" },
];

const POLICY_LINKS = [
  { label: "Privacy Policy", href: "/privacy" },
  { label: "Terms & Conditions", href: "/terms" },
  { label: "Grievance Redressal Policy", href: "/grievance" },
  { label: "Fair Lending Commitment", href: "/fair-practices" },
];

export function SiteFooter() {
  return (
    <footer className="site-footer">
      <div className="footer-main">
        <div className="container">
          <div className="footer-grid">
            <div className="footer-brand">
              <Brand />
              <p className="footer-blurb">
                A digital lending platform connecting borrowers with RBI-registered NBFC
                lending partners for fast, fully-online personal loans.
              </p>
              <p className="footer-reg">
                <b>{BRAND.legalName}</b>
                <br />
                A digital lending platform — not a lender.
                <br />
                CIN: <b>{BRAND.cin}</b>
              </p>
            </div>
            <div className="footer-col">
              <h4>Quick Links</h4>
              <ul>
                {QUICK_LINKS.map((l) => (
                  <li key={l.href}>
                    <Link href={l.href}>{l.label}</Link>
                  </li>
                ))}
              </ul>
            </div>
            <div className="footer-col">
              <h4>Policies</h4>
              <ul>
                {POLICY_LINKS.map((l) => (
                  <li key={l.label}>
                    <Link href={l.href}>{l.label}</Link>
                  </li>
                ))}
              </ul>
            </div>
            <div className="footer-col">
              <h4>Contact</h4>
              <div className="footer-contact-item">
                <MapPin />
                <span>
                  {BRAND.address.line1},<br />
                  {BRAND.address.line2} – {BRAND.address.pin}
                </span>
              </div>
              <div className="footer-contact-item">
                <Phone />
                <span>
                  <a href={BRAND.phoneHref}>{BRAND.phone}</a>
                </span>
              </div>
              <div className="footer-contact-item">
                <Mail />
                <span>
                  <a href={`mailto:${BRAND.email}`}>{BRAND.email}</a>
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>
      <div className="footer-disclaimer">
        <div className="container">
          <strong>Disclaimer:</strong> NAVIX ({BRAND.legalName}) is a digital lending platform,
          not a lender. All loans are sanctioned and disbursed by our RBI-registered NBFC lending
          partners. Loan approval is subject to the partner&apos;s credit policy and eligibility
          assessment. NAVIX does not charge any advance fee for loan processing.
        </div>
      </div>
      <div className="footer-bottom">
        <div className="container">
          <span>
            © 2026 {BRAND.legalName}. All rights reserved. · Built by{" "}
            <a href="https://softsolutionsai.com" target="_blank" rel="noopener noreferrer">softsolutionsai.com</a>
          </span>
          <span className="footer-legal-links">
            <Link href="/privacy">Privacy</Link>
            <Link href="/terms">Terms</Link>
            <Link href="/grievance">Grievance</Link>
            <a
              href="https://www.linkedin.com/company/softsolutionsai/"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="SoftSolutionsAI on LinkedIn"
              style={{ display: "inline-flex", alignItems: "center" }}
            >
              <Linkedin size={16} />
            </a>
          </span>
        </div>
      </div>
    </footer>
  );
}
