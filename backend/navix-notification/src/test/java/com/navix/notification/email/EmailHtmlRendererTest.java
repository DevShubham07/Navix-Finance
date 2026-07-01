package com.navix.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The branded HTML wrapper: structure, brand, escaping, and paragraph handling. */
class EmailHtmlRendererTest {

    private final EmailHtmlRenderer renderer = new EmailHtmlRenderer();

    @Test
    void rendersBrandedShellWithSubjectAndBody() {
        String html = renderer.render("Your loan is approved", "Hi Asha,\n\nGood news.");

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("NAVIX");                         // wordmark
        assertThat(html).contains("#0C2540");                       // brand navy
        assertThat(html).contains("Your loan is approved");         // subject in <title>/<h1>
        assertThat(html).contains("Good news.");                    // body
        assertThat(html).contains("NAVIX Finance");                 // footer
    }

    @Test
    void escapesHtmlInSubjectAndBody() {
        String html = renderer.render("<script>x</script>", "1 < 2 & 3 > 0");

        assertThat(html).doesNotContain("<script>x</script>");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("1 &lt; 2 &amp; 3 &gt; 0");
    }

    @Test
    void splitsBlankLineSeparatedBlocksIntoParagraphs() {
        String html = renderer.render("S", "para one\n\npara two");

        assertThat(html).contains("<p style=\"margin:0 0 14px;\">para one</p>");
        assertThat(html).contains("<p style=\"margin:0 0 14px;\">para two</p>");
    }

    @Test
    void handlesNullBody() {
        String html = renderer.render("S", null);
        assertThat(html).contains("<!DOCTYPE html>");
    }
}
