package com.erfeamor.cvdomain.experience;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for the person-scoped experience aggregate.
 */
public interface ExperienceRepository extends JpaRepository<Experience, Long> {

    /**
     * Ordered per docs/api-contract.md § Ordering: {@code startDate} DESC, tiebroken by {@code id}
     * ASC. Retrofitted by T-105 — T-101 shipped this collection unordered, before T-006 added the
     * rule.
     *
     * <p>Derived name rather than an {@code @Query}: it is the idiom the sibling resources use
     * (see {@code EducationRepository}) and it cannot drift from the entity's column mapping.
     * Unlike {@code project}, {@code experience.start_date} is {@code NOT NULL} in V1, so the
     * NULL-placement rule does not apply and the {@code CASE WHEN … IS NULL} spelling
     * {@code ProjectRepository} needs would be dead code here, implying a nullability the schema
     * does not have.
     *
     * <p>The {@code id} tiebreaker is mandatory, not decorative: two experiences starting the same
     * month would otherwise come back in arbitrary relative order, and cv-public-react's ISR
     * freezes whichever won that render into a cached page.
     */
    List<Experience> findByPersonIdOrderByStartDateDescIdAsc(Long personId);

    /**
     * Scoped single-row lookup: an experience is identified by the {@code (personId, id)} pair, so
     * a row owned by another person is simply not found. Callers must never use {@code findById}
     * plus an ownership comparison — a forgotten comparison is an IDOR.
     */
    Optional<Experience> findByIdAndPersonId(Long id, Long personId);
}
