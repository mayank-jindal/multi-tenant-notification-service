package com.notifly.notification.template;

import com.notifly.notification.common.model.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A stable, tenant-facing handle for a message.
 *
 * <p>Holds no content of its own — content lives in {@link TemplateVersion}. The split exists so
 * that editing a template cannot retroactively change what an already-queued notification says,
 * and so a delivery record from six months ago can still be resolved to the exact text that was
 * sent.
 */
@Entity
@Table(name = "templates")
public class Template extends TenantOwnedEntity {

    /** Tenant-chosen identifier quoted in send requests, unique within the tenant. */
    @Column(name = "code", nullable = false, length = 64, updatable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /**
     * Archived templates reject new sends but are never deleted, because delivery history
     * references them.
     */
    @Column(name = "archived", nullable = false)
    private boolean archived = false;

    protected Template() {
        // for JPA
    }

    public Template(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }
}
