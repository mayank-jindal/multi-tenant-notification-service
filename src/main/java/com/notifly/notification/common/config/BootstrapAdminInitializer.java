package com.notifly.notification.common.config;

import com.notifly.notification.user.User;
import com.notifly.notification.user.UserRepository;
import com.notifly.notification.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first platform administrator on an otherwise empty database.
 *
 * <p>Done here rather than in a Flyway migration because the password must be BCrypt-hashed, and
 * a migration would either have to embed a pre-computed hash — fixing the password forever — or
 * store it in plaintext.
 *
 * <p>Runs only when no platform administrator exists. It is not an upsert: if one is already
 * present, the configured password is ignored entirely, so a restart cannot silently reset the
 * credentials of an account someone has since changed.
 */
@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotiflyProperties properties;

    public BootstrapAdminInitializer(UserRepository userRepository,
                                     PasswordEncoder passwordEncoder,
                                     NotiflyProperties properties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.countByRole(UserRole.PLATFORM_ADMIN) > 0) {
            log.debug("Platform administrator already exists; bootstrap skipped");
            return;
        }

        NotiflyProperties.Bootstrap bootstrap = properties.bootstrap();
        User admin = User.platformAdmin(
                bootstrap.adminEmail(),
                passwordEncoder.encode(bootstrap.adminPassword()),
                bootstrap.adminDisplayName());
        userRepository.save(admin);

        log.info("""
                
                ============================================================
                 Bootstrap platform administrator created
                   email: {}
                 This account uses the configured bootstrap password. Change
                 it, or set ADMIN_PASSWORD, before exposing this service.
                ============================================================
                """, bootstrap.adminEmail());
    }
}
