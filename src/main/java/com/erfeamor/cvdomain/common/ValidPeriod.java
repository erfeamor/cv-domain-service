package com.erfeamor.cvdomain.common;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint for a {@link Dated} section: when both dates are present, {@code endDate}
 * must not be earlier than {@code startDate} (equal is allowed). When either is null the period is
 * not checked — required-ness stays with the per-field constraints (T-115, contract rule 4).
 *
 * <p>Rides the ordinary {@code @Valid} path, so a violation is the same default 400 a
 * {@code @NotNull} violation produces, reported against {@code endDate}.
 */
@Documented
@Constraint(validatedBy = ValidPeriodValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPeriod {

    /** Violation message; the violation is reported on the {@code endDate} property. */
    String message() default "must not be earlier than startDate";

    /** Validation groups. */
    Class<?>[] groups() default {};

    /** Payload for clients of the Bean Validation API. */
    Class<? extends Payload>[] payload() default {};
}
