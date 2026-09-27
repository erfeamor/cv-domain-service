package com.erfeamor.cvdomain.common;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.LocalDate;

/** Enforces {@link ValidPeriod}: only an inverted period with both dates present is invalid. */
public class ValidPeriodValidator implements ConstraintValidator<ValidPeriod, Dated> {

    @Override
    public boolean isValid(Dated value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        LocalDate start = value.getStartDate();
        LocalDate end = value.getEndDate();
        if (start == null || end == null || !end.isBefore(start)) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("endDate")
                .addConstraintViolation();
        return false;
    }
}
