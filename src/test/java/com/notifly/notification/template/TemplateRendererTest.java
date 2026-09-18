package com.notifly.notification.template;

import com.notifly.notification.common.error.ApiException;
import com.notifly.notification.common.model.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for template rendering. No Spring context — this is a pure function and the tests
 * that matter here run in milliseconds.
 */
class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    // ---------------------------------------------------------------- substitution

    @Nested
    @DisplayName("substitution")
    class Substitution {

        @Test
        @DisplayName("replaces declared variables in subject, text and html")
        void replacesAcrossAllParts() {
            TemplateChannelBody body = body(Channel.EMAIL,
                    "Order {{orderId}}", "Hi {{name}}", "<p>Hi {{name}}</p>");

            var rendered = renderer.render(body,
                    List.of(TemplateVariable.required("orderId"), TemplateVariable.required("name")),
                    Map.of("orderId", "A-1001", "name", "Mayank"));

            assertThat(rendered.subject()).isEqualTo("Order A-1001");
            assertThat(rendered.bodyText()).isEqualTo("Hi Mayank");
            assertThat(rendered.bodyHtml()).isEqualTo("<p>Hi Mayank</p>");
        }

        @Test
        @DisplayName("tolerates whitespace inside the braces")
        void toleratesWhitespace() {
            TemplateChannelBody body = body(Channel.SMS, null, "Hi {{  name  }}", null);

            var rendered = renderer.render(body,
                    List.of(TemplateVariable.required("name")), Map.of("name", "Sam"));

            assertThat(rendered.bodyText()).isEqualTo("Hi Sam");
        }

        @Test
        @DisplayName("replaces every occurrence, not just the first")
        void replacesRepeatedOccurrences() {
            TemplateChannelBody body = body(Channel.SMS, null, "{{name}}, {{name}}, {{name}}", null);

            var rendered = renderer.render(body,
                    List.of(TemplateVariable.required("name")), Map.of("name", "x"));

            assertThat(rendered.bodyText()).isEqualTo("x, x, x");
        }

        @Test
        @DisplayName("an optional variable falls back to its declared default")
        void optionalFallsBackToDefault() {
            TemplateChannelBody body = body(Channel.SMS, null, "Hi {{name}}", null);

            var rendered = renderer.render(body,
                    List.of(TemplateVariable.optional("name", "there")), Map.of());

            assertThat(rendered.bodyText()).isEqualTo("Hi there");
        }

        @Test
        @DisplayName("an optional variable with no default renders as empty")
        void optionalWithoutDefaultRendersEmpty() {
            TemplateChannelBody body = body(Channel.SMS, null, "Hi{{suffix}}", null);

            var rendered = renderer.render(body,
                    List.of(new TemplateVariable("suffix", false, null, null)), Map.of());

            assertThat(rendered.bodyText()).isEqualTo("Hi");
        }

        @Test
        @DisplayName("a value containing $ or backslash survives intact")
        void regexMetacharactersInValuesAreSafe() {
            // Matcher.appendReplacement treats $ and \ as syntax; without quoting, a price of
            // "$5" would corrupt the output or throw.
            TemplateChannelBody body = body(Channel.SMS, null, "Total: {{amount}}", null);

            var rendered = renderer.render(body,
                    List.of(TemplateVariable.required("amount")), Map.of("amount", "$5 \\ $1"));

            assertThat(rendered.bodyText()).isEqualTo("Total: $5 \\ $1");
        }

        @Test
        @DisplayName("a null body part stays null rather than becoming an empty string")
        void nullPartsAreLeftAlone() {
            TemplateChannelBody body = body(Channel.SMS, null, "text", null);

            var rendered = renderer.render(body, List.of(), Map.of());

            assertThat(rendered.subject()).isNull();
            assertThat(rendered.bodyHtml()).isNull();
        }
    }

    // ---------------------------------------------------------------- escaping

    @Nested
    @DisplayName("html escaping")
    class Escaping {

        @Test
        @DisplayName("values substituted into html are escaped")
        void htmlValuesAreEscaped() {
            // Without this, one recipient's name injects markup into everyone else's email.
            TemplateChannelBody body = body(Channel.EMAIL, null, null, "<p>Hi {{name}}</p>");

            var rendered = renderer.render(body,
                    List.of(TemplateVariable.required("name")),
                    Map.of("name", "<script>alert('xss')</script>"));

            assertThat(rendered.bodyHtml())
                    .doesNotContain("<script>")
                    .contains("&lt;script&gt;")
                    .contains("&#39;");
        }

        @Test
        @DisplayName("values substituted into plain text are not escaped")
        void plainTextIsNotEscaped() {
            // Escaping here would render "Tom & Jerry" as "Tom &amp; Jerry" in an SMS.
            TemplateChannelBody body = body(Channel.SMS, null, "{{name}}", null);

            var rendered = renderer.render(body,
                    List.of(TemplateVariable.required("name")), Map.of("name", "Tom & Jerry <3"));

            assertThat(rendered.bodyText()).isEqualTo("Tom & Jerry <3");
        }

        @Test
        @DisplayName("the template's own html is left untouched")
        void templateMarkupIsPreserved() {
            TemplateChannelBody body = body(Channel.EMAIL, null, null, "<b>Hi</b> {{name}}");

            var rendered = renderer.render(body,
                    List.of(TemplateVariable.required("name")), Map.of("name", "Sam"));

            assertThat(rendered.bodyHtml()).isEqualTo("<b>Hi</b> Sam");
        }
    }

    // ---------------------------------------------------------------- publish-time strictness

    @Nested
    @DisplayName("publish-time validation")
    class PublishValidation {

        @Test
        @DisplayName("a body using an undeclared variable is rejected")
        void undeclaredVariableIsRejected() {
            // The typo that this catches is the entire point of strict rendering.
            TemplateChannelBody body = body(Channel.SMS, null, "Hi {{customerNmae}}", null);

            assertThatThrownBy(() -> renderer.validateBodiesAgainstDeclaration(
                    List.of(TemplateVariable.required("customerName")), List.of(body)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("customerNmae");
        }

        @Test
        @DisplayName("declaring a variable no body uses is allowed")
        void unusedDeclarationIsAllowed() {
            // Harmless: a caller supplies it and nothing references it. Failing here would block
            // an author who declared variables before writing the copy.
            TemplateChannelBody body = body(Channel.SMS, null, "Hello", null);

            assertThatCode(() -> renderer.validateBodiesAgainstDeclaration(
                    List.of(TemplateVariable.required("unused")), List.of(body)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an unclosed placeholder is reported as malformed")
        void unclosedPlaceholderIsRejected() {
            TemplateChannelBody body = body(Channel.SMS, null, "Hi {{name", null);

            assertThatThrownBy(() -> renderer.validateBodiesAgainstDeclaration(
                    List.of(TemplateVariable.required("name")), List.of(body)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("malformed");
        }

        @Test
        @DisplayName("a placeholder whose name is not a valid identifier is malformed")
        void invalidNameIsRejected() {
            TemplateChannelBody body = body(Channel.SMS, null, "Hi {{ 123bad }}", null);

            assertThatThrownBy(() -> renderer.validateBodiesAgainstDeclaration(
                    List.of(), List.of(body)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("malformed");
        }

        @Test
        @DisplayName("validation covers the subject and html, not only the text body")
        void allPartsAreValidated() {
            TemplateChannelBody body = body(Channel.EMAIL, "{{undeclaredSubject}}", "fine", null);

            assertThatThrownBy(() -> renderer.validateBodiesAgainstDeclaration(
                    List.of(), List.of(body)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("undeclaredSubject");
        }

        @Test
        @DisplayName("a valid template passes")
        void validTemplatePasses() {
            TemplateChannelBody body = body(Channel.EMAIL,
                    "Order {{orderId}}", "Hi {{name}}", "<p>{{name}}</p>");

            assertThatCode(() -> renderer.validateBodiesAgainstDeclaration(
                    List.of(TemplateVariable.required("orderId"), TemplateVariable.required("name")),
                    List.of(body)))
                    .doesNotThrowAnyException();
        }
    }

    // ---------------------------------------------------------------- send-time strictness

    @Nested
    @DisplayName("send-time validation")
    class SendValidation {

        @Test
        @DisplayName("a missing required variable is rejected")
        void missingRequiredIsRejected() {
            assertThatThrownBy(() -> renderer.validateSuppliedVariables(
                    List.of(TemplateVariable.required("name")), Map.of()))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("name");
        }

        @Test
        @DisplayName("a blank value counts as missing")
        void blankCountsAsMissing() {
            assertThatThrownBy(() -> renderer.validateSuppliedVariables(
                    List.of(TemplateVariable.required("name")), Map.of("name", "   ")))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("name");
        }

        @Test
        @DisplayName("a supplied variable the template never declared is rejected")
        void unknownSuppliedVariableIsRejected() {
            // The same typo caught from the caller's side: silently ignoring it would let them
            // believe the message was personalised when it was not.
            assertThatThrownBy(() -> renderer.validateSuppliedVariables(
                    List.of(TemplateVariable.required("name")),
                    Map.of("name", "Sam", "nmae", "Sam")))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("nmae");
        }

        @Test
        @DisplayName("an omitted optional variable is fine")
        void omittedOptionalIsFine() {
            assertThatCode(() -> renderer.validateSuppliedVariables(
                    List.of(TemplateVariable.required("name"), TemplateVariable.optional("tone", "formal")),
                    Map.of("name", "Sam")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a template declaring nothing accepts no variables")
        void templateWithNoVariablesAcceptsNone() {
            assertThatCode(() -> renderer.validateSuppliedVariables(List.of(), Map.of()))
                    .doesNotThrowAnyException();

            assertThatThrownBy(() -> renderer.validateSuppliedVariables(List.of(), Map.of("x", "1")))
                    .isInstanceOf(ApiException.class);
        }
    }

    // ---------------------------------------------------------------- helper

    private TemplateChannelBody body(Channel channel, String subject, String text, String html) {
        TemplateChannelBody body = new TemplateChannelBody(UUID.randomUUID(), channel);
        body.setSubject(subject);
        body.setBodyText(text);
        body.setBodyHtml(html);
        return body;
    }
}
