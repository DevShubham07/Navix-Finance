import { redirect } from "next/navigation";

/** Payment settings moved into Staff → Settings. */
export default function PaymentSettingsRedirect() {
  redirect("/staff/admin/settings");
}
