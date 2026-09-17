package com.notifly.notification.template;

/** Lifecycle of a single immutable template version. */
public enum TemplateVersionStatus {

    /** Editable, not resolvable by a send request. */
    DRAFT,

    /**
     * The one version a send request resolves to. Publishing a new version archives the
     * previous one — a unique partial index guarantees at most one per template.
     */
    PUBLISHED,

    /** Previously published, retained so historical sends remain explicable. */
    ARCHIVED
}
