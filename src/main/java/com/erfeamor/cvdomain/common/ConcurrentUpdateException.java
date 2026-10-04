package com.erfeamor.cvdomain.common;

/**
 * The conditional UPDATE of a versioned {@code PUT} matched no row: between this transaction's
 * read and its write, another transaction committed either a DELETE of the row or an update of
 * it. Hibernate cannot tell the two apart (T-108, T-113).
 *
 * <p>Thrown from inside the {@code @Transactional} update method, so the transaction is rolled
 * back before the owning controller's handler runs. That handler then checks, in a fresh
 * transaction that sees committed state, whether the row still exists: gone is a {@code 404}
 * (T-108), still there is a {@code 409} (rule 8). Checking inside the failed transaction would be
 * wrong on InnoDB, whose REPEATABLE READ snapshot still shows the deleted row.
 */
public class ConcurrentUpdateException extends RuntimeException {

    private final Long id;

    public ConcurrentUpdateException(Long id, Throwable cause) {
        super("Row " + id + " changed or disappeared between read and write", cause);
        this.id = id;
    }

    public Long getId() {
        return id;
    }
}
