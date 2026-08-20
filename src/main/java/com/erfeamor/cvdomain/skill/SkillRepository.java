package com.erfeamor.cvdomain.skill;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for the global skill catalog.
 *
 * <p>There is deliberately no person-scoped finder here: the catalog is global (design rule 2,
 * DoR 7), and a {@code personId} parameter anywhere on this interface would be a contract
 * violation rather than a missing feature.
 */
public interface SkillRepository extends JpaRepository<Skill, Long> {

    /**
     * Ordered per docs/api-contract.md § Ordering: catalog skills by {@code name} ASC, tiebroken
     * by {@code id} ASC. The derived form is enough here — {@code skill.name} is {@code NOT NULL}
     * in V1, so there is no NULL placement to express (unlike the person assignments query).
     *
     * <p>The tiebreaker is mandatory, not decorative: {@code name} is {@code UNIQUE} so it can
     * never actually tie, but it is kept so the ordering contract reads the same for every
     * collection and survives a future relaxation of that constraint.
     */
    List<Skill> findAllByOrderByNameAscIdAsc();
}
