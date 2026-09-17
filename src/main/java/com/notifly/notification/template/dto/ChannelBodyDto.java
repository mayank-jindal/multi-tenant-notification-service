package com.notifly.notification.template.dto;

import com.notifly.notification.template.TemplateChannelBody;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * The content of one template version for one channel.
 *
 * <p>Which fields matter depends on the channel: EMAIL uses all three, PUSH treats
 * {@code subject} as the notification title, SMS and IN_APP use {@code bodyText} only.
 *
 * @param subject  email subject line, or push title
 * @param bodyText plain-text body
 * @param bodyHtml HTML body, email only
 */
@Schema(description = "Channel-specific content")
public record ChannelBodyDto(

        @Schema(example = "Your order {{orderId}} has shipped")
        @Size(max = 998, message = "must be at most 998 characters")
        String subject,

        @Schema(example = "Hi {{customerName}}, your order is on its way.")
        @Size(max = 100_000, message = "must be at most 100000 characters")
        String bodyText,

        @Size(max = 500_000, message = "must be at most 500000 characters")
        String bodyHtml) {

    /** A body with no content at all would render as an empty message. */
    public boolean hasContent() {
        return (bodyText != null && !bodyText.isBlank()) || (bodyHtml != null && !bodyHtml.isBlank());
    }

    public static ChannelBodyDto from(TemplateChannelBody body) {
        return new ChannelBodyDto(body.getSubject(), body.getBodyText(), body.getBodyHtml());
    }
}
