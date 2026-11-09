package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fixed, branded emails a DSA may send their own lead. The DSA picks one — they never write the
 * subject or body — so nothing free-text leaves the platform's sending domain. Rendered server-side so
 * the dialog's preview and the sent email are the same HTML. Table layout + inline styles for email
 * clients; images are absolute URLs on the public site.
 */
final class DsaEmailTemplates {

    static final String SITE = "https://dhanboost.com";
    static final String APPLY_URL = SITE + "/signup/otp";
    static final String LOGO_URL = SITE + "/dhanboost-mark.png";

    record Template(String key, String name, String subject, String headline, String intro, String text,
                    String contentHtml) {
    }

    record Rendered(String key, String name, String subject, String html, String text) {
    }

    private static final Map<String, Template> TEMPLATES = new LinkedHashMap<>();

    static {
        add(new Template("INTRO", "Introduction",
                "{{NAME}}, get a salary advance before payday",
                "Money before payday,<br>repaid on payday.",
                "Short on cash before your salary lands? DhanBoost gives salaried professionals a quick advance of up to <strong>25% of their monthly salary</strong>, repaid in one go on salary day.",
                "Short on cash before your salary lands? DhanBoost gives salaried professionals an advance of up to 25% of their monthly salary, repaid in one go on salary day.",
                features(
                        "&#9889;", "Fully digital", "Apply from your phone in minutes &mdash; no branch visits.",
                        "&#128197;", "Salary-day repayment", "One repayment, due on your salary day.",
                        "&#128176;", "Pay early, save", "Interest is charged only for the days you keep the money.")));
        add(new Template("FOLLOW_UP", "Gentle follow-up",
                "{{NAME}}, your DhanBoost advance is a few taps away",
                "Still thinking it over?",
                "We saved you a spot. Checking your offer takes a few minutes and does not commit you to anything &mdash; you only borrow once you accept the terms.",
                "We saved you a spot. Checking your offer takes a few minutes and does not commit you to anything - you only borrow once you accept the terms.",
                checklist(
                        "Keep your PAN and Aadhaar handy",
                        "Have your latest salary slip ready",
                        "Use the mobile number linked to your Aadhaar")));
        add(new Template("HOW_IT_WORKS", "How it works",
                "How DhanBoost works, in 3 simple steps",
                "Three steps to your advance.",
                "Here is exactly what happens when you apply with DhanBoost &mdash; transparent from the first screen to repayment.",
                "Here is exactly what happens when you apply with DhanBoost - transparent from the first screen to repayment.",
                steps(
                        "Check your offer", "Sign in with your mobile number, share your salary details and complete paperless KYC.",
                        "Choose and sign", "Pick an amount within your limit, review the Key Fact Statement and e-sign.",
                        "Get paid, repay once", "The money reaches your bank account; repay once on your salary day.")));
    }

    private DsaEmailTemplates() {
    }

    private static void add(Template t) {
        TEMPLATES.put(t.key(), t);
    }

    static List<Rendered> renderAll(String leadName) {
        return TEMPLATES.keySet().stream().map(k -> render(k, leadName)).toList();
    }

    static Rendered render(String key, String leadName) {
        Template t = key == null ? null : TEMPLATES.get(key.trim().toUpperCase());
        if (t == null) {
            throw new BusinessException("INVALID_TEMPLATE", "Pick one of the email templates");
        }
        String first = firstName(leadName);
        String subject = first != null ? t.subject().replace("{{NAME}}", first)
                : capitalize(t.subject().replace("{{NAME}}, ", ""));
        String greeting = first == null ? "Hi there," : "Hi " + escape(first) + ",";
        String html = SHELL
                .replace("{{SUBJECT}}", escape(subject))
                .replace("{{LOGO}}", LOGO_URL)
                .replace("{{SITE}}", SITE)
                .replace("{{APPLY}}", APPLY_URL)
                .replace("{{HEADLINE}}", t.headline())
                .replace("{{GREETING}}", greeting)
                .replace("{{INTRO}}", t.intro())
                .replace("{{CONTENT}}", t.contentHtml())
                .replace("{{YEAR}}", String.valueOf(LocalDate.now().getYear()));
        String text = (first == null ? "Hi there," : "Hi " + first + ",") + "\n\n" + t.text()
                + "\n\nCheck your offer: " + APPLY_URL + "\n\n- Team DhanBoost";
        return new Rendered(t.key(), t.name(), subject, html, text);
    }

    private static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        // The DSA types the lead's name and it lands in an email from our domain: only a plain
        // letters-only first name is used, so it cannot carry a link, domain or other copy.
        String first = name.trim().split("\\s+")[0].toLowerCase();
        return first.matches("\\p{L}[\\p{L}'-]{0,29}") ? capitalize(first) : null;
    }

    private static String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String features(String... f) {
        StringBuilder sb = new StringBuilder(
                "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>");
        for (int i = 0; i < f.length; i += 3) {
            sb.append("<td class=\"stack\" width=\"33%\" valign=\"top\" style=\"padding:6px;\">")
                    .append("<div style=\"background:#FDFBF6;border:1px solid #F1E6C8;border-radius:14px;padding:18px 14px;text-align:center;\">")
                    .append("<div style=\"font-size:28px;line-height:1;\">").append(f[i]).append("</div>")
                    .append("<div style=\"margin-top:10px;font-weight:700;font-size:14px;color:#0C2540;\">").append(f[i + 1]).append("</div>")
                    .append("<div style=\"margin-top:6px;font-size:12.5px;line-height:1.5;color:#5b6b7c;\">").append(f[i + 2]).append("</div>")
                    .append("</div></td>");
        }
        return sb.append("</tr></table>").toString();
    }

    private static String checklist(String... items) {
        StringBuilder sb = new StringBuilder(
                "<div style=\"background:#FDFBF6;border:1px solid #F1E6C8;border-radius:14px;padding:18px 20px;\">"
                        + "<div style=\"font-weight:700;font-size:14px;color:#0C2540;margin-bottom:10px;\">Before you start</div>");
        for (String item : items) {
            sb.append("<div style=\"font-size:14px;line-height:1.6;color:#33404f;padding:4px 0;\">")
                    .append("<span style=\"display:inline-block;width:20px;height:20px;line-height:20px;border-radius:10px;background:#14746F;color:#ffffff;text-align:center;font-size:12px;margin-right:10px;\">&#10003;</span>")
                    .append(item).append("</div>");
        }
        return sb.append("</div>").toString();
    }

    private static String steps(String... s) {
        StringBuilder sb = new StringBuilder(
                "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">");
        for (int i = 0; i < s.length; i += 2) {
            sb.append("<tr><td width=\"48\" valign=\"top\" style=\"padding:8px 0;\">")
                    .append("<div style=\"width:36px;height:36px;line-height:36px;border-radius:18px;background:#E9B53A;color:#0C2540;font-weight:800;font-size:16px;text-align:center;\">")
                    .append(i / 2 + 1).append("</div></td>")
                    .append("<td valign=\"top\" style=\"padding:8px 0;\">")
                    .append("<div style=\"font-weight:700;font-size:15px;color:#0C2540;\">").append(s[i]).append("</div>")
                    .append("<div style=\"margin-top:3px;font-size:13.5px;line-height:1.55;color:#5b6b7c;\">").append(s[i + 1]).append("</div>")
                    .append("</td></tr>");
        }
        return sb.append("</table>").toString();
    }

    private static final String SHELL = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <title>{{SUBJECT}}</title>
          <style>
            @media only screen and (max-width:620px) {
              .wrap { width:100% !important; }
              .pad { padding-left:22px !important; padding-right:22px !important; }
              .stack { display:block !important; width:100% !important; }
              .hero h1 { font-size:26px !important; }
            }
          </style>
        </head>
        <body style="margin:0;padding:0;background-color:#F4F1E8;-webkit-text-size-adjust:100%;">
          <div style="display:none;max-height:0;overflow:hidden;opacity:0;font-size:1px;line-height:1px;">{{SUBJECT}}</div>
          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background-color:#F4F1E8;">
            <tr>
              <td align="center" style="padding:28px 12px;">
                <table role="presentation" class="wrap" width="600" cellpadding="0" cellspacing="0" border="0" style="width:600px;max-width:600px;background-color:#ffffff;border-radius:20px;overflow:hidden;font-family:'Hanken Grotesk',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                  <tr>
                    <td class="pad" style="padding:20px 36px;background-color:#ffffff;">
                      <a href="{{SITE}}" style="text-decoration:none;">
                        <img src="{{LOGO}}" width="40" height="40" alt="DhanBoost" style="vertical-align:middle;border:0;">
                        <span style="vertical-align:middle;margin-left:8px;font-family:'Bricolage Grotesque',Georgia,serif;font-size:22px;font-weight:700;color:#0C2540;">DhanBoost<span style="color:#E9B53A;">.</span></span>
                      </a>
                    </td>
                  </tr>
                  <tr>
                    <td class="pad hero" style="padding:40px 36px 44px;background-color:#0C2540;background-image:linear-gradient(135deg,#0C2540 0%,#14466B 60%,#14746F 100%);">
                      <div style="display:inline-block;padding:5px 12px;border-radius:999px;background:rgba(233,181,58,0.18);color:#E9B53A;font-size:11px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;">Salary-linked advance</div>
                      <h1 style="margin:16px 0 0;font-family:'Bricolage Grotesque',Georgia,serif;font-size:32px;line-height:1.2;font-weight:800;color:#ffffff;">{{HEADLINE}}</h1>
                      <p style="margin:14px 0 26px;font-size:15px;line-height:1.6;color:#C9D6E3;">Up to 25% of your monthly salary &middot; one repayment &middot; 100% online</p>
                      <a href="{{APPLY}}" style="display:inline-block;padding:14px 30px;border-radius:999px;background-color:#E9B53A;color:#0C2540;font-size:15px;font-weight:800;text-decoration:none;">Check my offer &rarr;</a>
                    </td>
                  </tr>
                  <tr>
                    <td class="pad" style="padding:34px 36px 10px;">
                      <p style="margin:0 0 12px;font-size:16px;font-weight:700;color:#0C2540;">{{GREETING}}</p>
                      <p style="margin:0 0 22px;font-size:15px;line-height:1.65;color:#33404f;">{{INTRO}}</p>
                      {{CONTENT}}
                    </td>
                  </tr>
                  <tr>
                    <td class="pad" align="center" style="padding:26px 36px 34px;">
                      <a href="{{APPLY}}" style="display:inline-block;padding:14px 34px;border-radius:999px;background-color:#0C2540;color:#ffffff;font-size:15px;font-weight:700;text-decoration:none;">Apply on DhanBoost</a>
                      <p style="margin:12px 0 0;font-size:12px;color:#8493a3;">Checking your offer takes about 5 minutes.</p>
                    </td>
                  </tr>
                  <tr>
                    <td class="pad" style="padding:22px 36px 28px;background-color:#FDFBF6;border-top:1px solid #F1E6C8;">
                      <p style="margin:0 0 8px;font-size:12px;line-height:1.6;color:#5b6b7c;">You are receiving this because a DhanBoost partner shared your details with us at your request. Loans are subject to eligibility and credit assessment; fees and interest are shown upfront in the Key Fact Statement before you sign.</p>
                      <p style="margin:0;font-size:11px;line-height:1.5;color:#a8b4c1;">&copy; {{YEAR}} DhanBoost &middot; NAVIX Finance Private Limited &middot; <a href="{{SITE}}" style="color:#5b6b7c;">dhanboost.com</a></p>
                    </td>
                  </tr>
                </table>
              </td>
            </tr>
          </table>
        </body>
        </html>
        """;
}
