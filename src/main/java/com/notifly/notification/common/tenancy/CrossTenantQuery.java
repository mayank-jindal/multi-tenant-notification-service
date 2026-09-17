package com.notifly.notification.common.tenancy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that deliberately reads across tenant boundaries.
 *
 * <p>Cross-tenant access is legitimate in exactly one place — platform administration — and
 * nowhere else. Requiring it to be annotated means every such method is greppable, reviewable,
 * and countable, rather than being an invisible property of some query someone wrote.
 *
 * <p>The annotation documents and locates intent; it does not grant anything. Authorization is
 * still enforced by {@code @PreAuthorize}, and visibility is still governed by the tenant scope
 * in force.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface CrossTenantQuery {

    /** Why this method is permitted to see more than one tenant's data. */
    String value();
}
