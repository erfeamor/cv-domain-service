package com.erfeamor.cvdomain.experience;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for the person-scoped experience aggregate.
 */
public interface ExperienceRepository extends JpaRepository<Experience, Long> {

    List<Experience> findByPersonId(Long personId);

    /**
     * Scoped single-row lookup: an experience is identified by the {@code (personId, id)} pair, so
     * a row owned by another person is simply not found. Callers must never use {@code findById}
     * plus an ownership comparison — a forgotten comparison is an IDOR.
     */
    Optional<Experience> findByIdAndPersonId(Long id, Long personId);
}
