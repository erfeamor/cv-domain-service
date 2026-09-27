package com.erfeamor.cvdomain.common;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * T-115: the period rule in isolation. Only an inverted period with both dates present is
 * invalid; every other combination is left to the per-field constraints.
 */
class ValidPeriodValidatorTest {

    private static final LocalDate EARLY = LocalDate.of(2021, 1, 1);
    private static final LocalDate LATE = LocalDate.of(2023, 5, 1);

    private static ValidatorFactory factory;
    private static Validator validator;

    @ValidPeriod
    private record Period(LocalDate start, LocalDate end) implements Dated {

        @Override
        public LocalDate getStartDate() {
            return start;
        }

        @Override
        public LocalDate getEndDate() {
            return end;
        }
    }

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private Set<ConstraintViolation<Period>> validate(LocalDate start, LocalDate end) {
        return validator.validate(new Period(start, end));
    }

    @Test
    void bothNullIsValid() {
        assertThat(validate(null, null)).isEmpty();
    }

    @Test
    void startOnlyIsValid() {
        assertThat(validate(LATE, null)).isEmpty();
    }

    @Test
    void endOnlyIsValid() {
        assertThat(validate(null, EARLY)).isEmpty();
    }

    @Test
    void equalDatesAreValid() {
        assertThat(validate(LATE, LATE)).isEmpty();
    }

    @Test
    void orderedDatesAreValid() {
        assertThat(validate(EARLY, LATE)).isEmpty();
    }

    @Test
    void invertedDatesAreInvalidAndReportedOnEndDate() {
        Set<ConstraintViolation<Period>> violations = validate(LATE, EARLY);

        assertThat(violations).hasSize(1);
        ConstraintViolation<Period> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("endDate");
        assertThat(violation.getMessage()).isEqualTo("must not be earlier than startDate");
    }
}
