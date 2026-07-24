import Link from "next/link";
import { BrandMark } from "./brand-mark";

/** Public marketing footer — ported from the design export (classes styled by marketing-theme.css). */
export function MarketingFooter() {
  return (
    <footer className="footer">
      <div className="wrap">
        <div className="f-grid">
          <div>
            <Link href="/" className="brand">
              <BrandMark />
              <span className="brand-txt">
                <b>DhanBoost</b>
                <small>Lending Platform</small>
              </span>
            </Link>
            <p className="f-about">
              A premium digital lending platform offering fast, fully-online, fairly-priced
              personal loans — salary-linked, with a single repayment and no advance fees.
            </p>
            <p className="f-legal">
              <b>NAVIX Finance Private Limited</b>
              <br />
              A digital lending platform.
              <br />
              CIN: <b>U64990HR2026PTC144926</b>
            </p>
          </div>
          <div className="fcol">
            <h5>Company</h5>
            <Link href="/about">About Us</Link>
            <Link href="/products">Loan Products</Link>
            <Link href="/careers">Careers</Link>
            <Link href="/blog">Resources</Link>
          </div>
          <div className="fcol">
            <h5>Policies</h5>
            <Link href="/privacy">Privacy Policy</Link>
            <Link href="/terms">Terms &amp; Conditions</Link>
            <Link href="/fair-practices">Fair Practices Code</Link>
            <Link href="/grievance">Grievance Redressal</Link>
            <Link href="/faq">FAQs</Link>
          </div>
          <div className="fcol f-contact">
            <h5>Get in touch</h5>
            <div>
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z" />
                <circle cx="12" cy="10" r="3" />
              </svg>
              <span>
                Dev Nagar,
                <br />
                Gurugram – 122102
              </span>
            </div>
            <div>
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.96.36 1.9.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.91.34 1.85.57 2.81.7A2 2 0 0 1 22 16.92z" />
              </svg>
              <span>+91 97167 60246</span>
            </div>
            <div>
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <rect x="2" y="4" width="20" height="16" rx="2" />
                <path d="m22 7-10 5L2 7" />
              </svg>
              <span>info@dhanboost.com</span>
            </div>
          </div>
        </div>
        <div className="f-disc">
          <b>Disclaimer:</b> DhanBoost (NAVIX Finance Private Limited) operates a digital lending
          platform offering salary-linked personal loans. Loan approval is subject to DhanBoost&apos;s
          credit policy and eligibility assessment. DhanBoost does not charge any advance fee for loan
          processing. Representative APR and all charges are disclosed before you accept any offer.
          Please borrow responsibly.
        </div>
        <div className="f-bottom">
          <span>
            © 2026 NAVIX Finance Private Limited. All rights reserved. · Built by{" "}
            <a href="https://softsolutionsai.com" target="_blank" rel="noopener noreferrer">softsolutionsai.com</a>
          </span>
          <span className="fb-links">
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
              <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                <path d="M4.98 3.5A2.5 2.5 0 1 1 0 3.5a2.5 2.5 0 0 1 4.98 0zM.5 8h4V24h-4V8zM8 8h3.8v2.2h.05c.53-1 1.83-2.2 3.77-2.2 4.03 0 4.78 2.65 4.78 6.1V24h-4v-6.9c0-1.65-.03-3.77-2.3-3.77-2.3 0-2.65 1.8-2.65 3.65V24H8V8z" />
              </svg>
            </a>
          </span>
        </div>
      </div>
    </footer>
  );
}
