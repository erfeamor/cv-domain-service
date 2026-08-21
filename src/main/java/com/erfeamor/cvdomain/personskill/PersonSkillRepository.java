package com.erfeamor.cvdomain.personskill;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Data access for person-scoped skill assignments, keyed by the composite {@link PersonSkillId}.
 */
public interface PersonSkillRepository extends JpaRepository<PersonSkill, PersonSkillId> {

    /**
     * Ordered per docs/api-contract.md § Ordering: {@code category} ASC with uncategorized
     * <strong>last</strong>, then {@code name} ASC, then {@code skillId} ASC.
     *
     * <p>Two things here cannot be expressed by a derived method name, and both were live defects
     * on sibling resources:
     *
     * <ul>
     *   <li>{@code skill.category} is nullable and MySQL sorts NULL lowest, so the natural order
     *       puts uncategorized skills <em>first</em> — the exact opposite of the contract. The
     *       {@code CASE} expression is the portable spelling of the contract's {@code ORDER BY
     *       category IS NULL, …}: {@code IS NULL} as a sort key is MySQL-specific, and {@code
     *       NULLS LAST} relies on the dialect emulating it, whereas this renders identically on
     *       H2 (tests) and MySQL (production).</li>
     *   <li>The tiebreaker is {@code skillId}, <strong>not</strong> {@code id} — {@code
     *       person_skill} has no {@code id} column for a derived {@code OrderBy…IdAsc} to bind
     *       to.</li>
     * </ul>
     *
     * <p>The {@code JOIN FETCH} is not incidental: every element of the response carries the
     * catalog's {@code name} and {@code category}, so fetching them with the collection is what
     * keeps this one query instead of one per assignment.
     */
    @Query("SELECT ps FROM PersonSkill ps JOIN FETCH ps.skill s WHERE ps.id.personId = :personId "
            + "ORDER BY CASE WHEN s.category IS NULL THEN 1 ELSE 0 END, s.category ASC, "
            + "s.name ASC, ps.id.skillId ASC")
    List<PersonSkill> findByPersonIdOrdered(@Param("personId") Long personId);
}
