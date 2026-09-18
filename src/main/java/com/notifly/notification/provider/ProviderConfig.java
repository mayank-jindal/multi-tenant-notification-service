package com.notifly.notification.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Registers real provider adapters when they are configured.
 *
 * <p>The simulator is the default for every channel, so the project runs and its tests pass with
 * no third-party account anywhere. Setting {@code notifly.providers.email.mode=SMTP} swaps in real
 * delivery; nothing else in the application changes, which is the point of the SPI.
 */
@Configuration
public class ProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(ProviderConfig.class);

    /**
     * Real SMTP delivery for the EMAIL channel.
     *
     * <p>{@link ProviderRegistry} prefers any discovered bean over a simulator, so publishing this
     * one is the entire integration.
     */
    @Bean
    @ConditionalOnProperty(prefix = "notifly.providers.email", name = "mode", havingValue = "SMTP")
    public ChannelProvider smtpEmailProvider(
            JavaMailSender mailSender,
            @Value("${spring.mail.username:}") String fromAddress,
            @Value("${notifly.providers.email.from-name:Notifly}") String fromName) {

        if (fromAddress.isBlank()) {
            // Failing here is better than starting and having every email fail at dispatch with a
            // confusing relay error.
            throw new IllegalStateException(
                    "notifly.providers.email.mode is SMTP but spring.mail.username is not set");
        }

        log.info("EMAIL channel will use real SMTP delivery, sending as {}", fromAddress);
        return new SmtpEmailProvider(mailSender, fromAddress, fromName);
    }
}
