package com.erfeamor.cvdomain.common;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

/**
 * Spring Data picks this up as the implementation of {@link VersionBumping} by its name. Calls go
 * through the repository proxy, so a stale-row failure is translated into Spring's
 * {@code ObjectOptimisticLockingFailureException} exactly as {@code flush()}'s is.
 *
 * <p>{@code PESSIMISTIC_FORCE_INCREMENT} rather than {@code OPTIMISTIC_FORCE_INCREMENT}: the
 * optimistic mode defers the increment to commit, after the update method has returned and can
 * no longer translate the failure; the pessimistic mode issues it immediately.
 *
 * @param <T> the entity type
 */
public class VersionBumpingImpl<T> implements VersionBumping<T> {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void forceVersionIncrement(T entity) {
        entityManager.lock(entity, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    }
}
