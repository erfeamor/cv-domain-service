package com.erfeamor.cvdomain.common;

import java.time.LocalDate;

/**
 * A CV section with a period. Either date may be null: a null {@code endDate} means "current"
 * (contract rule 3), and a project may carry neither.
 */
public interface Dated {

    LocalDate getStartDate();

    LocalDate getEndDate();
}
