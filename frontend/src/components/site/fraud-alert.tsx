"use client";

import * as React from "react";
import { AlertTriangle } from "lucide-react";
import { BRAND } from "@/lib/brand";

/** Dismissible security/fraud notice bar under the header. */
export function FraudAlert() {
  const [hidden, setHidden] = React.useState(false);
  if (hidden) return null;
  return (
    <div className="fraud-alert" role="alert">
      <div className="container">
        <AlertTriangle className="fa-icon" />
        <p>
          <strong>Security notice:</strong> DhanBoost never asks for advance fees or upfront
          payments. Beware of fraudulent apps or agents using our name. Report suspicious
          activity to <a href={`mailto:${BRAND.fraudEmail}`}>{BRAND.fraudEmail}</a>.
        </p>
        <button className="fraud-close" aria-label="Dismiss notice" onClick={() => setHidden(true)}>
          ×
        </button>
      </div>
    </div>
  );
}
