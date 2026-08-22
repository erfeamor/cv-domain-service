package com.erfeamor.cvdomain.project;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Data access for the person-scoped project aggregate.
 */
public interface ProjectRepository extends JpaRepository<Project, Long> {

    /**
     * Ordered per docs/api-contract.md § Ordering: {@code startDate} DESC with undated projects
     * <strong>last</strong>, tiebroken by {@code id} ASC.
     *
     * <p>This is the one collection on this service whose sort key is nullable, and the reason it
     * needs an explicit {@code @Query}: {@code project.start_date} is nullable in V1, MySQL sorts
     * NULL lowest, and the derived {@code findByPersonIdOrderByStartDateDescIdAsc} the sibling
     * resources use would compile, read correctly and return undated projects <em>first</em> —
     * the exact opposite of the rule. Spring Data's derived names cannot express a synthetic sort
     * key at all, so this cannot be fixed by renaming the method.
     *
     * <p>The {@code CASE} expression is the portable spelling of the contract's {@code ORDER BY
     * start_date IS NULL, …}: {@code IS NULL} as a sort key is SQL, not JPQL, so the contract's
     * literal wording does not parse here (open docs defect T-027). {@code CASE} renders
     * identically on H2 (tests) and MySQL 8.4 (production) and keeps the query JPQL, so a column
     * rename still fails at startup rather than at runtime — which {@code nativeQuery = true}
     * would not.
     *
     * <p>The tiebreaker is mandatory, not decorative: two projects starting the same month would
     * otherwise come back in arbitrary relative order, and cv-public-react's ISR freezes whichever
     * won that render into a cached page.
     */
    @Query("SELECT p FROM Project p WHERE p.person.id = :personId "
            + "ORDER BY CASE WHEN p.startDate IS NULL THEN 1 ELSE 0 END, p.startDate DESC, "
            + "p.id ASC")
    List<Project> findByPersonIdOrdered(@Param("personId") Long personId);

    /**
     * Scoped single-row lookup: a project is identified by the {@code (personId, id)} pair, so a
     * row owned by another person is simply not found. Callers must never use {@code findById}
     * plus an ownership comparison — a forgotten comparison is an IDOR.
     */
    Optional<Project> findByIdAndPersonId(Long id, Long personId);
}
