package com.erfeamor.cvdomain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Persistence coverage for the global skill catalog (test-plan case P3, plus catalog ordering).
 */
@DataJpaTest
class SkillRepositoryTest {

    @Autowired
    private SkillRepository skillRepository;

    @Autowired
    private TestEntityManager entityManager;

    /** Round-trips both columns, including a null category read back through the raw column. */
    @Test
    void roundTripsNameAndNullableCategory() {
        Skill saved = skillRepository.saveAndFlush(new Skill("Vim", null));
        entityManager.clear();

        Skill found = skillRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("Vim");
        assertThat(found.getCategory()).isNull();

        // Read through the physical columns, so the mapping is asserted rather than assumed, and
        // an uncategorized skill is a plain SQL NULL rather than an empty-string sentinel.
        Object[] row = (Object[]) entityManager.getEntityManager()
                .createNativeQuery("SELECT name, category FROM skill WHERE id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();
        assertThat(row[0]).isEqualTo("Vim");
        assertThat(row[1]).isNull();
    }

    /**
     * P3 — {@code skill.name} is UNIQUE, and that constraint is what makes the controller's 409
     * catch path correct rather than optimistic.
     *
     * <p>The explicit {@code flush()} is required, not stylistic: without it H2 defers the INSERT
     * to the end of the transaction and this test passes whether or not the constraint exists.
     */
    @Test
    void p3RejectsADuplicateSkillName() {
        skillRepository.saveAndFlush(new Skill("Java", "Backend"));

        assertThatThrownBy(() -> skillRepository.saveAndFlush(new Skill("Java", "Other")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Case sensitivity is the database's, not the entity's — documented, not asserted here. */
    @Test
    void catalogIsOrderedByNameThenId() {
        skillRepository.save(new Skill("Vim", null));
        skillRepository.save(new Skill("Java", "Backend"));
        skillRepository.save(new Skill("Ansible", "Ops"));
        skillRepository.flush();
        entityManager.clear();

        List<Skill> catalog = skillRepository.findAllByOrderByNameAscIdAsc();

        // Insertion order is deliberately the reverse of the expected order, so a query that
        // simply returned rows in natural order would fail here.
        assertThat(catalog).extracting(Skill::getName)
                .containsExactly("Ansible", "Java", "Vim");
    }
}
