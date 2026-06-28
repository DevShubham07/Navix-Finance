package com.navix.notification.template;

import com.navix.common.notification.NotificationChannel;
import com.navix.notification.catalog.NotificationType;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Renders a {@code (type, channel)} template against a model by substituting {@code {key}} tokens.
 * Generalises {@code BorrowerOtpService.buildMessage}'s {@code {otp}}/{@code {ttl}} approach. An
 * unknown/absent key renders as {@code —} (never leaks a raw {@code {placeholder}} to a recipient).
 */
@Component
public class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)\\}");

    private final NotificationTemplates templates;

    public TemplateRenderer(NotificationTemplates templates) {
        this.templates = templates;
    }

    /** Render the template for {@code type}+{@code channel}, or {@code null} if none is defined. */
    public RenderedMessage render(NotificationType type, NotificationChannel channel, Map<String, Object> model) {
        TemplateDef def = templates.get(type, channel);
        if (def == null) {
            return null;
        }
        return new RenderedMessage(channel, substitute(def.subject(), model), substitute(def.body(), model));
    }

    static String substitute(String template, Map<String, Object> model) {
        if (template == null) {
            return null;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object value = model == null ? null : model.get(m.group(1));
            String text = value == null ? "—" : String.valueOf(value);
            m.appendReplacement(out, Matcher.quoteReplacement(text));
        }
        m.appendTail(out);
        return out.toString();
    }
}
