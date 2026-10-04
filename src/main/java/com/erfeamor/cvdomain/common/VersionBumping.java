package com.erfeamor.cvdomain.common;

/**
 * Repository fragment for the versioned resources (T-113): increments an entity's
 * {@code @Version} even when none of its fields changed.
 *
 * <p>Decision: <strong>every successful {@code PUT} increments the version</strong>, including
 * one whose body equals the stored values. Hibernate's dirty check issues no UPDATE for such a
 * body, so without this the version would stay put, and a DELETE committed between the PUT's read
 * and its (absent) write would go unnoticed: a {@code 200} echoing a row that no longer exists
 * (the gap T-108 left open). The forced UPDATE is a current read on every engine, so it sees the
 * DELETE and fails like any other stale write.
 *
 * @param <T> the entity type
 */
public interface VersionBumping<T> {

    /**
     * Issues {@code UPDATE … SET version = version + 1 WHERE id = ? AND version = ?} now, for a
     * managed entity. Only for an entity the current transaction did not just update: the dirty
     * flush already incremented that one, and calling this would increment it twice.
     *
     * @param entity a managed entity
     */
    void forceVersionIncrement(T entity);
}
