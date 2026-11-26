/* eslint-disable */
import { BRAND } from "@/lib/brand";

/** The official contact directory shared by the DB-LGL-002/003/004 policy pages. */
export const POLICY_CONTACTS = `<ul>
      <li>Website: <b>www.dhanboost.com</b></li>
      <li>Customer support: <b>${BRAND.phone}</b> (${BRAND.hours})</li>
      <li>WhatsApp support: <b>${BRAND.whatsapp}</b></li>
      <li>Support email: <b>${BRAND.email}</b></li>
      <li>General enquiries: <b>${BRAND.infoEmail}</b></li>
      <li>Grievance email: <b>${BRAND.grievanceEmail}</b></li>
      <li>Grievance contact: <b>${BRAND.grievanceOfficer.phone}</b></li>
    </ul>`;
