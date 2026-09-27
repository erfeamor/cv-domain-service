package com.erfeamor.cvdomain.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * T-115: proves a 400 came from {@link ValidPeriod} on the ordinary {@code @Valid} path. With
 * problem details off, every MockMvc validation 400 has an empty body, so the status alone cannot
 * tell this path from any other handler.
 */
public final class PeriodViolation {

    private PeriodViolation() {
    }

    /** The request failed Bean Validation with a {@code ValidPeriod} error on {@code endDate}. */
    public static ResultMatcher rejectedByValidPeriod() {
        return result -> {
            assertThat(result.getResolvedException())
                    .isInstanceOf(MethodArgumentNotValidException.class);
            MethodArgumentNotValidException ex =
                    (MethodArgumentNotValidException) result.getResolvedException();
            assertThat(ex.getBindingResult().getFieldErrors())
                    .extracting(FieldError::getField, FieldError::getCode)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("endDate", "ValidPeriod"));
        };
    }
}
