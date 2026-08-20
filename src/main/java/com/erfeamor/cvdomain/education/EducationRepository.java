package com.erfeamor.cvdomain.education;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for the person-scoped education aggregate.
 */
public interface EducationRepository extends JpaRepository<Education, Long> {

    /**
     * Ordered per docs/api-contract.md § Ordering: {@code startDate} DESC, tiebroken by {@code id}
     * ASC. The tiebreaker is mandatory, not decorative — two entries starting the same month would
     * otherwise come back in arbitrary relative order, and cv-public-react's ISR freezes whichever
     * won that render into a cached page.
     *
     * <p>Ordering lives here rather than in the controller so there is exactly one answer for
     * every consumer. {@code education.start_date} is NOT NULL in V1, so this deliberately carries
     * no NULL handling — that would be dead code implying a nullability the schema does not have.
     */
    List<Education> findByPersonIdOrderByStartDateDescIdAsc(Long personId);

    /**
     * Scoped single-row lookup: an education entry is identified by the {@code (personId, id)}
     * pair, so a row owned by another person is simply not found. Callers must never use {@code
     * findById} plus an ownership comparison — a forgotten comparison is an IDOR.
     */
    Optional<Education> findByIdAndPersonId(Long id, Long personId);
}
