package com.navix.app.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** The V97 seed: every SMS has as many variables as placeholders, and bureau SMS need a confirmation. */
class AdminTemplateSeedTest {

    private static final Pattern ROW = Pattern.compile(
            "\\('(DHANBOOST_\\w+)', '(\\d{19})', '(\\[.*?\\])',\\s+'(.*?)',\\s+(null|'.*?')\\)", Pattern.DOTALL);

    private static String migration() throws IOException {
        return new ClassPathResource("db/migration/V97__admin_templates.sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void eachSeededSmsHasOneVariablePerPlaceholder() throws IOException {
        Matcher m = ROW.matcher(migration());
        int rows = 0;
        while (m.find()) {
            rows++;
            int placeholders = m.group(4).split(Pattern.quote("{#var#}"), -1).length - 1;
            int variables = "[]".equals(m.group(3)) ? 0 : m.group(3).split(",").length;
            assertThat(variables).as(m.group(1)).isEqualTo(placeholders);
            assertThat(m.group(4)).as(m.group(1)).endsWith(" - DhanBoost").matches("\\p{ASCII}+");
            if (m.group(1).contains("REPORTED")) {
                assertThat(m.group(5)).as(m.group(1)).contains("confirmed in writing");
            }
        }
        assertThat(rows).isEqualTo(9);
    }
}
