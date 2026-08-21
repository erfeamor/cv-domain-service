package com.erfeamor.cvdomain.personskill;

/**
 * The proficiency levels of a person-skill assignment.
 *
 * <p>These constant names are the physical values of MySQL's native {@code ENUM} column in V1
 * ({@code 'BEGINNER','INTERMEDIATE','ADVANCED','EXPERT'}) and the literal strings the contract
 * publishes. Persisted with {@code EnumType.STRING}: renaming or reordering a constant is a
 * database migration, not a refactor.
 */
public enum Proficiency {
    BEGINNER,
    INTERMEDIATE,
    ADVANCED,
    EXPERT
}
