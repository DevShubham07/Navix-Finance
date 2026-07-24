package com.navix.notification.email;

import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Wraps a notification's plain-text body in a branded, responsive HTML email — DhanBoost wordmark, the
 * 2026 brand palette (navy {@code #0C2540} · gold {@code #E9B53A} · cream {@code #FDFBF6}), and a
 * transactional footer with company details. Table-based layout + inline styles for broad email-client
 * support. The same rendered HTML is handed to every real provider (SES / Resend / SMTP); the
 * plain-text body rides along as the fallback alternative.
 */
@Component
public class EmailHtmlRenderer {

    private static final String TEMPLATE = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <meta http-equiv="X-UA-Compatible" content="IE=edge">
          <title>{{SUBJECT}}</title>
        </head>
        <body style="margin:0;padding:0;background-color:#FDFBF6;-webkit-text-size-adjust:100%;">
          <div style="display:none;max-height:0;overflow:hidden;opacity:0;color:#FDFBF6;font-size:1px;line-height:1px;">{{SUBJECT}}</div>
          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background-color:#FDFBF6;">
            <tr>
              <td align="center" style="padding:28px 12px;">
                <table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" style="width:600px;max-width:600px;background-color:#ffffff;border-radius:16px;overflow:hidden;border:1px solid #eef1f5;">
                  <tr>
                    <td style="background-color:#0C2540;padding:26px 36px;">
                      <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
                        <tr>
                          <td style="font-family:'Bricolage Grotesque',Georgia,'Times New Roman',serif;font-size:26px;font-weight:700;letter-spacing:0.5px;color:#ffffff;">DhanBoost<span style="color:#E9B53A;">.</span></td>
                          <td align="right" style="font-family:Arial,Helvetica,sans-serif;font-size:11px;font-weight:700;letter-spacing:2px;text-transform:uppercase;color:#9fb0c4;">Finance</td>
                        </tr>
                      </table>
                    </td>
                  </tr>
                  <tr><td style="height:4px;line-height:4px;font-size:0;background-color:#E9B53A;">&nbsp;</td></tr>
                  <tr>
                    <td style="padding:36px 36px 24px;font-family:'Hanken Grotesk',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                      <h1 style="margin:0 0 18px;font-family:'Bricolage Grotesque',Georgia,serif;font-size:21px;line-height:1.3;font-weight:700;color:#0C2540;">{{SUBJECT}}</h1>
                      <div style="font-size:15px;line-height:1.65;color:#33404f;">{{BODY}}</div>
                    </td>
                  </tr>
                  <tr><td style="padding:0 36px;"><div style="border-top:1px solid #eef1f5;font-size:0;line-height:0;">&nbsp;</div></td></tr>
                  <tr>
                    <td style="padding:22px 36px 30px;font-family:'Hanken Grotesk',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                      <p style="margin:0 0 10px;font-size:13px;line-height:1.6;color:#5b6b7c;"><strong style="color:#0C2540;">DhanBoost</strong> &mdash; salary-linked advances, repaid once on your salary day.</p>
                      <p style="margin:0 0 10px;font-size:12px;line-height:1.6;color:#8493a3;">This is a transactional message about your DhanBoost account; please do not reply. Need help? Visit <a href="https://dhanboost.com" style="color:#0C2540;text-decoration:underline;">dhanboost.com</a>.</p>
                      <p style="margin:0;font-size:11px;line-height:1.5;color:#a8b4c1;">&copy; {{YEAR}} DhanBoost. All rights reserved. &middot; Confidential</p>
                    </td>
                  </tr>
                </table>
                <p style="width:600px;max-width:600px;margin:16px auto 0;font-family:Arial,Helvetica,sans-serif;font-size:11px;line-height:1.5;color:#a8b4c1;text-align:center;">You received this email because you have an account with DhanBoost.</p>
              </td>
            </tr>
          </table>
        </body>
        </html>
        """;

    /** Render the branded HTML for a notification. {@code subject}/{@code body} are HTML-escaped. */
    public String render(String subject, String body) {
        String safeSubject = escape(subject == null || subject.isBlank() ? "DhanBoost" : subject);
        return TEMPLATE
                .replace("{{SUBJECT}}", safeSubject)
                .replace("{{BODY}}", toHtmlParagraphs(body))
                .replace("{{YEAR}}", String.valueOf(LocalDate.now().getYear()));
    }

    /** Escape, then split blank-line-separated blocks into paragraphs and single newlines into breaks. */
    private static String toHtmlParagraphs(String body) {
        String escaped = escape(body == null ? "" : body).trim();
        if (escaped.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String para : escaped.split("\\n\\s*\\n")) {
            sb.append("<p style=\"margin:0 0 14px;\">")
                    .append(para.trim().replace("\n", "<br>"))
                    .append("</p>");
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
