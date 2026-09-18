package com.notifly.notification.provider;

import com.notifly.notification.common.model.Channel;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.io.UnsupportedEncodingException;
import java.util.UUID;

/**
 * Delivers email over SMTP.
 *
 * <p>The real counterpart to {@link SimulatedChannelProvider} for the EMAIL channel, and the
 * demonstration that the provider SPI is a genuine seam rather than a shape drawn around a
 * simulator: nothing outside this class changed to make real delivery work.
 *
 * <p>Enabled only when {@code notifly.providers.email.mode} is {@code SMTP}. The simulator remains
 * the default so the project still runs, and its tests still pass, with no mail account
 * configured.
 *
 * <p><strong>On the From address.</strong> Most SMTP relays — Gmail and Outlook among them —
 * refuse to send with a From address other than the authenticated account. The tenant's configured
 * sender identity is therefore used as the display name and as Reply-To, while the envelope sender
 * stays the authenticated account. Silently sending as the wrong address would be worse than
 * saying so. A production deployment would use a relay supporting per-tenant verified domains,
 * which is a configuration change here rather than a code change.
 */
public class SmtpEmailProvider implements ChannelProvider {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailProvider.class);

    public static final String PROVIDER_CODE = "SMTP";

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String defaultFromName;

    public SmtpEmailProvider(JavaMailSender mailSender, String fromAddress, String defaultFromName) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.defaultFromName = defaultFromName;
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public ProviderResult send(OutboundMessage message) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");

            applyFrom(helper, message);
            helper.setTo(message.recipient());
            helper.setSubject(message.subject() == null ? "(no subject)" : message.subject());

            // Both parts when HTML exists, so a client that will not render HTML still has
            // something readable rather than an empty message.
            if (message.bodyHtml() != null && !message.bodyHtml().isBlank()) {
                helper.setText(
                        message.bodyText() == null ? "" : message.bodyText(),
                        message.bodyHtml());
            } else {
                helper.setText(message.bodyText() == null ? "" : message.bodyText(), false);
            }

            mailSender.send(mime);

            // SMTP gives no useful identifier back, so the Message-ID we generated is recorded.
            String messageId = mime.getMessageID() != null
                    ? mime.getMessageID()
                    : "smtp-" + UUID.randomUUID();

            log.debug("Delivered email to {} via SMTP ({})", message.recipient(), messageId);
            return ProviderResult.success(messageId);

        } catch (MailAuthenticationException ex) {
            // The relay rejected our credentials. Retrying cannot succeed until somebody changes
            // configuration, so spending the retry budget on it would be pure waste — and the
            // failure would be buried under four identical ones.
            log.error("SMTP authentication failed; check the configured credentials", ex);
            return ProviderResult.permanentFailure("SMTP_AUTH_FAILED",
                    "The mail relay rejected the configured credentials");

        } catch (MailParseException | UnsupportedEncodingException ex) {
            // A malformed address or unencodable content. The same message will fail identically
            // every time.
            return ProviderResult.permanentFailure("SMTP_MESSAGE_INVALID",
                    "Message could not be constructed: " + ex.getMessage());

        } catch (MailSendException ex) {
            return classifySendFailure(ex);

        } catch (Exception ex) {
            // Unknown failures are treated as transient. That risks a wasted retry, whereas
            // assuming permanent would discard a message that might well have gone through.
            log.warn("Unexpected SMTP failure for {}", message.recipient(), ex);
            return ProviderResult.transientFailure("SMTP_ERROR",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void applyFrom(MimeMessageHelper helper, OutboundMessage message)
            throws jakarta.mail.MessagingException, UnsupportedEncodingException {
        String displayName = message.senderIdentity() == null || message.senderIdentity().isBlank()
                ? defaultFromName
                : message.senderIdentity();

        helper.setFrom(fromAddress, displayName);

        // The tenant's configured identity is honoured for replies even though it cannot be the
        // envelope sender, so a reply reaches the tenant rather than the relay account.
        if (message.senderIdentity() != null && message.senderIdentity().contains("@")) {
            helper.setReplyTo(message.senderIdentity());
        }
    }

    /**
     * Separates "this address will never work" from "the relay is having a moment".
     *
     * <p>SMTP encodes this in its reply codes: 5xx is permanent, 4xx is temporary. The distinction
     * is the entire basis of the retry policy, so it is read from the failure rather than guessed.
     */
    private ProviderResult classifySendFailure(MailSendException ex) {
        String detail = ex.getMessage() == null ? "" : ex.getMessage();

        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause.getMessage() != null) {
                detail = detail + " | " + cause.getMessage();
            }
            cause = cause.getCause();
        }

        String lower = detail.toLowerCase();

        boolean permanent =
                lower.contains("550") || lower.contains("551") || lower.contains("553")
                || lower.contains("554") || lower.contains("invalid address")
                || lower.contains("no such user") || lower.contains("recipient rejected")
                || lower.contains("does not exist");

        if (permanent) {
            return ProviderResult.permanentFailure("SMTP_RECIPIENT_REJECTED", truncate(detail));
        }

        // 4xx, connection refused, timeouts, greylisting — all worth another attempt.
        return ProviderResult.transientFailure("SMTP_TEMPORARY_FAILURE", truncate(detail));
    }

    /** Keeps a verbose SMTP trace from filling the error column of every failed row. */
    private String truncate(String value) {
        String cleaned = value.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= 500 ? cleaned : cleaned.substring(0, 497) + "...";
    }
}
