import { Phone, Mail, Clock } from "lucide-react";
import { BRAND } from "@/lib/brand";

/** Top utility bar — phone, email, hours, and the RBI-partner note. */
export function UtilityBar() {
  return (
    <div className="utility-bar">
      <div className="container">
        <div className="utility-left">
          <span>
            <Phone width={13} height={13} />
            <a href={BRAND.phoneHref}>{BRAND.phone}</a>
          </span>
          <span className="utility-hide">
            <Mail width={13} height={13} />
            <a href={`mailto:${BRAND.email}`}>{BRAND.email}</a>
          </span>
          <span className="utility-hide">
            <Clock width={13} height={13} />
            {BRAND.hours}
          </span>
        </div>
        <div className="utility-right">
          <span className="utility-reg">Loans by RBI-registered NBFC partners</span>
        </div>
      </div>
    </div>
  );
}
