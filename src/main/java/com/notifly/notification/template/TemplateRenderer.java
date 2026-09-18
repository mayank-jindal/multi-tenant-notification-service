package com.notifly.notification.template;

import com.notifly.notification.common.error.ErrorCode;
import com.notifly.notification.common.error.Errors;
import com.notifly.notification.common.model.Channel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {@code {{variable}}} placeholders, strictly.
 *
 * <p>"Strict" means three specific things, each of which turns a silent production failure into a
 * loud one:
 *
 * <ol>
 *   <li>A placeholder that is not declared by the version fails at <strong>publish</strong>, not
 *       at send. A typo like {@code {{customerNmae}}} is caught once, by the author, instead of
 *       rendering as an empty string in every message thereafter.</li>
 *   <li>A required variable with no supplied value fails at <strong>submission</strong>, before
 *       any work is queued — so the caller gets a 400 rather than a batch of half-rendered
 *       messages that were technically delivered.</li>
 *   <li>A supplied variable the template never declared also fails at submission. This is the
 *       same typo caught from the other side: silently ignoring it would let a caller believe
 *       they had personalised a message when they had not.</li>
 * </ol>
 *
 * <p>Values substituted into an HTML body are HTML-escaped; values substituted into plain text are
 * not. Without that, a recipient name containing markup would be injected into the email body of
 * every other recipient who received the same template.
 */
@Component
public class TemplateRenderer {

    /**
     * A placeholder: {@code {{name}}}, tolerating surrounding whitespace. Names match the same
     * grammar the variable declaration DTO enforces, so a name that can be declared can be used.
     */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_.]*)\\s*}}");

    /**
     * An opening {@code {{} that never forms a valid placeholder — an unclosed brace, or a name
     * the grammar rejects. Detected separately so it can be reported as malformed rather than
     * silently surviving into the rendered output.
     */
    private static final Pattern MALFORMED =
            Pattern.compile("\\{\\{(?!\\s*[a-zA-Z_][a-zA-Z0-9_.]*\\s*}})");

    // ---------------------------------------------------------------- inspection

    /** Every distinct placeholder name in a body, in the order they first appear. */
    public Set<String> placeholdersIn(String text) {
        if (text == null || text.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** Placeholders across a body's subject, text and HTML parts. */
    public Set<String> placeholdersIn(TemplateChannelBody body) {
        Set<String> names = new LinkedHashSet<>();
        names.addAll(placeholdersIn(body.getSubject()));
        names.addAll(placeholdersIn(body.getBodyText()));
        names.addAll(placeholdersIn(body.getBodyHtml()));
        return names;
    }

    // ---------------------------------------------------------------- publish-time validation

    /**
     * Checks a version's bodies against its declared variables.
     *
     * <p>Runs at publish. This is the moment the author is present and can fix a typo; at send
     * time the author is long gone and the only options are to fail a caller's request or to send
     * something wrong.
     *
     * @throws com.notifly.notification.common.error.ApiException if any body references an
     *                                                            undeclared variable or contains
     *                                                            a malformed placeholder
     */
    public void validateBodiesAgainstDeclaration(List<TemplateVariable> declared,
                                                 List<TemplateChannelBody> bodies) {
        Set<String> declaredNames = declared.stream()
                .map(TemplateVariable::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Set<String> undeclared = new TreeSet<>();
        List<String> malformed = new ArrayList<>();

        for (TemplateChannelBody body : bodies) {
            checkMalformed(body.getChannel(), "subject", body.getSubject(), malformed);
            checkMalformed(body.getChannel(), "bodyText", body.getBodyText(), malformed);
            checkMalformed(body.getChannel(), "bodyHtml", body.getBodyHtml(), malformed);

            for (String used : placeholdersIn(body)) {
                if (!declaredNames.contains(used)) {
                    undeclared.add(used);
                }
            }
        }

        if (!malformed.isEmpty()) {
            throw Errors.badRequest(ErrorCode.VALIDATION_FAILED,
                            "Template contains malformed placeholders; each must be of the form {{variableName}}")
                    .with("malformed", malformed);
        }

        if (!undeclared.isEmpty()) {
            throw Errors.badRequest(ErrorCode.TEMPLATE_VARIABLE_UNDECLARED,
                            "Template bodies use variables that are not declared: " + String.join(", ", undeclared))
                    .with("undeclaredVariables", List.copyOf(undeclared))
                    .with("declaredVariables", List.copyOf(declaredNames));
        }
    }

    // ---------------------------------------------------------------- send-time rendering

    /**
     * Validates supplied values against the declared contract.
     *
     * <p>Called once per submission rather than once per recipient: the contract is the same for
     * every notification in a batch, and checking it ten thousand times would be ten thousand
     * identical answers.
     */
    public void validateSuppliedVariables(List<TemplateVariable> declared, Map<String, Object> supplied) {
        Map<String, Object> values = supplied == null ? Map.of() : supplied;

        Set<String> declaredNames = declared.stream()
                .map(TemplateVariable::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Set<String> missing = new TreeSet<>();
        for (TemplateVariable variable : declared) {
            if (!variable.required()) {
                continue;
            }
            Object value = values.get(variable.name());
            if (value == null || String.valueOf(value).isBlank()) {
                missing.add(variable.name());
            }
        }

        if (!missing.isEmpty()) {
            throw Errors.badRequest(ErrorCode.TEMPLATE_VARIABLE_MISSING,
                            "Required template variables are missing: " + String.join(", ", missing))
                    .with("missingVariables", List.copyOf(missing));
        }

        Set<String> unknown = new TreeSet<>(values.keySet());
        unknown.removeAll(declaredNames);
        if (!unknown.isEmpty()) {
            // Rejected rather than ignored: a caller who misspells a variable name would
            // otherwise believe they had personalised the message when they had not.
            throw Errors.badRequest(ErrorCode.TEMPLATE_VARIABLE_UNDECLARED,
                            "Variables supplied that this template does not declare: " + String.join(", ", unknown))
                    .with("unknownVariables", List.copyOf(unknown))
                    .with("declaredVariables", List.copyOf(declaredNames));
        }
    }

    /**
     * Renders one channel body.
     *
     * <p>Assumes {@link #validateSuppliedVariables} has already passed, so unresolved required
     * variables cannot reach here. Optional variables fall back to their declared default, then
     * to an empty string.
     */
    public RenderedContent render(TemplateChannelBody body,
                                  List<TemplateVariable> declared,
                                  Map<String, Object> supplied) {
        Map<String, String> resolved = resolveValues(declared, supplied);

        return new RenderedContent(
                substitute(body.getSubject(), resolved, false),
                substitute(body.getBodyText(), resolved, false),
                substitute(body.getBodyHtml(), resolved, true));
    }

    // ---------------------------------------------------------------- internals

    private Map<String, String> resolveValues(List<TemplateVariable> declared, Map<String, Object> supplied) {
        Map<String, Object> values = supplied == null ? Map.of() : supplied;
        Map<String, String> resolved = new HashMap<>();

        for (TemplateVariable variable : declared) {
            Object value = values.get(variable.name());
            if (value != null && !String.valueOf(value).isBlank()) {
                resolved.put(variable.name(), String.valueOf(value));
            } else if (variable.defaultValue() != null) {
                resolved.put(variable.name(), variable.defaultValue());
            } else {
                resolved.put(variable.name(), "");
            }
        }
        return resolved;
    }

    private String substitute(String text, Map<String, String> values, boolean escapeHtml) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length());

        while (matcher.find()) {
            String name = matcher.group(1);
            String value = values.getOrDefault(name, "");
            // appendReplacement treats $ and \ in the replacement as syntax, so a value
            // containing either would corrupt the output or throw.
            matcher.appendReplacement(out, Matcher.quoteReplacement(escapeHtml ? escapeHtml(value) : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Escapes the five characters that can break out of HTML text or an attribute value.
     *
     * <p>Without this, a recipient name of {@code <script>...</script>} would be injected into the
     * email body of everyone who received that template.
     */
    private String escapeHtml(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private void checkMalformed(Channel channel, String field, String text, List<String> collected) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher matcher = MALFORMED.matcher(text);
        while (matcher.find()) {
            int from = matcher.start();
            int to = Math.min(text.length(), from + 30);
            collected.add(channel.name() + "." + field + ": " + text.substring(from, to));
        }
    }

    /**
     * The rendered result for one channel.
     *
     * @param subject  rendered subject, or null if the body had none
     * @param bodyText rendered plain text
     * @param bodyHtml rendered HTML, with substituted values HTML-escaped
     */
    public record RenderedContent(String subject, String bodyText, String bodyHtml) {
    }
}
