package com.notifly.notification.template;

import com.notifly.notification.common.model.Channel;
import com.notifly.notification.common.model.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * The channel-specific content of one template version.
 *
 * <p>A single logical message ("your order shipped") reads very differently as an email and as an
 * SMS, so each channel gets its own body rather than one body being truncated to fit. Which
 * fields are meaningful depends on the channel: EMAIL uses all three, PUSH uses subject as the
 * title plus a text body, SMS and IN_APP use text only.
 */
@Entity
@Table(name = "template_channel_bodies")
public class TemplateChannelBody extends TenantOwnedEntity {

    @Column(name = "template_version_id", nullable = false, updatable = false)
    private UUID templateVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16, updatable = false)
    private Channel channel;

    @Column(name = "subject", length = 998)
    private String subject;

    @Column(name = "body_text", columnDefinition = "text")
    private String bodyText;

    @Column(name = "body_html", columnDefinition = "text")
    private String bodyHtml;

    protected TemplateChannelBody() {
        // for JPA
    }

    public TemplateChannelBody(UUID templateVersionId, Channel channel) {
        this.templateVersionId = templateVersionId;
        this.channel = channel;
    }

    public UUID getTemplateVersionId() {
        return templateVersionId;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getBodyText() {
        return bodyText;
    }

    public void setBodyText(String bodyText) {
        this.bodyText = bodyText;
    }

    public String getBodyHtml() {
        return bodyHtml;
    }

    public void setBodyHtml(String bodyHtml) {
        this.bodyHtml = bodyHtml;
    }
}
