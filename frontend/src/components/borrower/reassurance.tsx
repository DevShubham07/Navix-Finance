import * as React from "react";
import { Lock, BadgeCheck, IndianRupee } from "lucide-react";

/** Trust strip reused under wizard steps — encrypted, transparent, no advance fee. */
export function Reassurance() {
  return (
    <div className="reassure-strip">
      <span className="ri">
        <Lock size={18} /> 256-bit encrypted
      </span>
      <span className="ri">
        <BadgeCheck size={18} /> Transparent terms
      </span>
      <span className="ri">
        <IndianRupee size={18} /> No advance fee
      </span>
    </div>
  );
}
