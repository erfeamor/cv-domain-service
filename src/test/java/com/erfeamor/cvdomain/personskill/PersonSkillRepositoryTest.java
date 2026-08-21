package com.erfeamor.cvdomain.personskill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erfeamor.cvdomain.person.Person;
import com.erfeamor.cvdomain.person.PersonRepository;
import com.erfeamor.cvdomain.skill.Skill;
import com.erfeamor.cvdomain.skill.SkillRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

/**
 * Persistence coverage for person-skill assignments (test-plan cases P1, P2, P4-P7, plus the
 * assignment ordering).
 */
@DataJpaTest
class PersonSkillRepositoryTest {

    @Autowired
    private PersonSkillRepository personSkillRepository;

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private SkillRepository skillRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Person persistPerson(String email) {
        return personRepository.saveAndFlush(
                new Person("Jane Doe", "Engineer", email, "Remote", "Bio"));
    }

    private Skill persistSkill(String name, String category) {
        return skillRepository.saveAndFlush(new Skill(name, category));
    }

    private long countRows(Long personId, Long skillId) {
        Number count = (Number) entityManager.getEntityManager()
                .createNativeQuery("SELECT COUNT(*) FROM person_skill "
                        + "WHERE person_id = :personId AND skill_id = :skillId")
                .setParameter("personId", personId)
                .setParameter("skillId", skillId)
                .getSingleResult();
        return count.longValue();
    }

    /**
     * P1 — the composite key's {@code equals}/{@code hashCode}, and the classic silent failure
     * this aggregate can have.
     *
     * <p>The lookup uses a <strong>freshly constructed</strong> {@code PersonSkillId}, never the
     * instance that was saved, and runs after {@code flush()} + {@code clear()} so it must resolve
     * through the key rather than the persistence context. With an identity-based (inherited)
     * {@code equals}, this returns empty for a row that plainly exists — and every controller path
     * that builds an id from path variables silently 404s.
     */
    @Test
    void p1FindsARowByAFreshlyConstructedCompositeId() {
        Person person = persistPerson("p1@example.com");
        Skill skill = persistSkill("Java", "Backend");
        personSkillRepository.saveAndFlush(
                new PersonSkill(person, skill, Proficiency.ADVANCED));
        entityManager.clear();

        PersonSkillId lookup = new PersonSkillId(person.getId(), skill.getId());
        assertThat(personSkillRepository.findById(lookup)).isPresent();
        // The value semantics themselves, stated directly: a second instance of the same pair is
        // the same key, and equal keys must agree on hashCode or a HashMap-backed lookup misses.
        assertThat(lookup).isEqualTo(new PersonSkillId(person.getId(), skill.getId()))
                .hasSameHashCodeAs(new PersonSkillId(person.getId(), skill.getId()));
        assertThat(lookup).isNotEqualTo(new PersonSkillId(person.getId(), skill.getId() + 1));
    }

    /**
     * P2 — every enum value round-trips, and the raw column holds the constant's NAME.
     *
     * <p>The native read is the point: with {@code EnumType.ORDINAL} the Java round-trip still
     * passes on H2 while MySQL's native {@code ENUM} column silently stores the wrong level (or
     * rejects the write). This asserts the string the migration declares.
     */
    @Test
    void p2RoundTripsEveryProficiencyAndStoresItAsAString() {
        Person person = persistPerson("p2@example.com");
        for (Proficiency proficiency : Proficiency.values()) {
            Skill skill = persistSkill("Skill-" + proficiency, "Backend");
            personSkillRepository.saveAndFlush(new PersonSkill(person, skill, proficiency));
            entityManager.clear();

            PersonSkill reloaded = personSkillRepository
                    .findById(new PersonSkillId(person.getId(), skill.getId())).orElseThrow();
            assertThat(reloaded.getProficiency()).isSameAs(proficiency);

            Object raw = entityManager.getEntityManager()
                    .createNativeQuery("SELECT proficiency FROM person_skill "
                            + "WHERE person_id = :personId AND skill_id = :skillId")
                    .setParameter("personId", person.getId())
                    .setParameter("skillId", skill.getId())
                    .getSingleResult();
            assertThat(raw).hasToString(proficiency.name());
        }
    }

    /** P4 — deleting a person removes their assignments; the catalog entry survives. */
    @Test
    void p4DeletingAPersonCascadesToAssignmentsButNotToTheCatalog() {
        Person person = persistPerson("p4@example.com");
        Skill skill = persistSkill("Java", "Backend");
        personSkillRepository.saveAndFlush(new PersonSkill(person, skill, Proficiency.EXPERT));
        entityManager.clear();

        personRepository.deleteById(person.getId());
        personRepository.flush();
        entityManager.clear();

        assertThat(countRows(person.getId(), skill.getId())).isZero();
        // A skill is shared: removing one person must not strip it from the catalog.
        assertThat(skillRepository.findById(skill.getId())).isPresent();
    }

    /**
     * P5 — deleting a catalog skill removes that skill's assignments for <em>every</em> person,
     * and leaves the people themselves alone.
     *
     * <p>No API exposes a catalog delete (DoR 9), so this exercises the FK at the repository
     * layer — it is also the reason DoR 9 exists: the cascade makes a catalog delete quietly
     * rewrite other people's CVs.
     */
    @Test
    void p5DeletingASkillCascadesToEveryPersonsAssignments() {
        Person first = persistPerson("p5a@example.com");
        Person second = persistPerson("p5b@example.com");
        Skill skill = persistSkill("Java", "Backend");
        personSkillRepository.save(new PersonSkill(first, skill, Proficiency.EXPERT));
        personSkillRepository.save(new PersonSkill(second, skill, Proficiency.BEGINNER));
        personSkillRepository.flush();
        entityManager.clear();

        skillRepository.deleteById(skill.getId());
        skillRepository.flush();
        entityManager.clear();

        assertThat(countRows(first.getId(), skill.getId())).isZero();
        assertThat(countRows(second.getId(), skill.getId())).isZero();
        assertThat(personRepository.findById(first.getId())).isPresent();
        assertThat(personRepository.findById(second.getId())).isPresent();
    }

    /** P6(a) — load the managed row, mutate it, save: an in-place update, still one row. */
    @Test
    void p6aUpsertThroughAManagedEntityUpdatesInPlace() {
        Person person = persistPerson("p6a@example.com");
        Skill skill = persistSkill("Java", "Backend");
        personSkillRepository.saveAndFlush(new PersonSkill(person, skill, Proficiency.BEGINNER));
        entityManager.clear();

        PersonSkillId id = new PersonSkillId(person.getId(), skill.getId());
        PersonSkill existing = personSkillRepository.findById(id).orElseThrow();
        existing.setProficiency(Proficiency.ADVANCED);
        personSkillRepository.saveAndFlush(existing);
        entityManager.clear();

        assertThat(countRows(person.getId(), skill.getId())).isEqualTo(1);
        assertThat(personSkillRepository.findById(id).orElseThrow().getProficiency())
                .isSameAs(Proficiency.ADVANCED);
    }

    /**
     * P6(b) — the variant that actually models the controller's upsert, and the one that breaks if
     * the entity leaves its composite id to be derived at flush time.
     *
     * <p>A <strong>brand-new</strong> {@code PersonSkill} carrying the same pair is handed
     * straight to {@code save()}. Because the id is populated in the constructor, Spring Data sees
     * a non-new entity and merges over the existing row; if it were null, {@code save()} would
     * call {@code persist()} and the primary key would reject a second insert. The explicit
     * {@code flush()} between the read and the write is what proves the sequential semantics that
     * {@code @WebMvcTest} cannot observe, since it mocks the repository away.
     */
    @Test
    void p6bUpsertThroughAFreshlyConstructedEntityUpdatesInPlace() {
        Person person = persistPerson("p6b@example.com");
        Skill skill = persistSkill("Java", "Backend");
        personSkillRepository.saveAndFlush(new PersonSkill(person, skill, Proficiency.BEGINNER));
        entityManager.clear();

        PersonSkillId id = new PersonSkillId(person.getId(), skill.getId());
        assertThat(personSkillRepository.findById(id)).isPresent();
        entityManager.flush();
        entityManager.clear();

        Person detachedPerson = personRepository.findById(person.getId()).orElseThrow();
        Skill detachedSkill = skillRepository.findById(skill.getId()).orElseThrow();
        personSkillRepository.saveAndFlush(
                new PersonSkill(detachedPerson, detachedSkill, Proficiency.EXPERT));
        entityManager.clear();

        assertThat(countRows(person.getId(), skill.getId())).isEqualTo(1);
        assertThat(personSkillRepository.findById(id).orElseThrow().getProficiency())
                .isSameAs(Proficiency.EXPERT);
    }

    /**
     * P7 — a null proficiency is rejected, and the entity is the layer responsible.
     *
     * <p>V1's column carries {@code DEFAULT 'INTERMEDIATE'}, which is unreachable through
     * Hibernate: it writes an explicit value on every INSERT, including an explicit NULL, so the
     * DDL default never applies. {@code @NotNull} on the field is what turns this into a 400 at
     * the API rather than a constraint-violation 500 — the DB's {@code NOT NULL} is the backstop
     * underneath it, not the primary check.
     */
    @Test
    void p7RejectsANullProficiencyRatherThanFallingBackToTheColumnDefault() {
        Person person = persistPerson("p7@example.com");
        Skill skill = persistSkill("Java", "Backend");

        assertThatThrownBy(() -> personSkillRepository
                .saveAndFlush(new PersonSkill(person, skill, null)))
                .isInstanceOf(jakarta.validation.ConstraintViolationException.class);
    }

    /**
     * The assignment ordering, end to end against a real query: {@code category} ASC with
     * uncategorized <strong>last</strong>, then {@code name} ASC, then {@code skillId} ASC.
     *
     * <p>The two uncategorized rows are the whole point — MySQL sorts NULL lowest, so a query
     * without the explicit NULL-placement key returns them <em>first</em> and this assertion
     * fails. A fixture with no null category would pass against the wrong implementation.
     *
     * <p>The {@code skillId} tiebreaker cannot be exercised by data: it only applies when two rows
     * share both category and name, and {@code skill.name} is UNIQUE. It is asserted by review of
     * the query, and it matters because {@code person_skill} has no {@code id} column for the
     * usual tiebreaker to bind to.
     */
    @Test
    void assignmentsAreOrderedByCategoryWithUncategorizedLastThenName() {
        Person person = persistPerson("order@example.com");
        Person other = persistPerson("other@example.com");
        // Inserted in an order that is wrong on every key, so natural order cannot pass.
        Skill vim = persistSkill("Vim", null);
        Skill terraform = persistSkill("Terraform", "Ops");
        Skill java = persistSkill("Java", "Backend");
        Skill ansible = persistSkill("Ansible", "Ops");
        Skill bash = persistSkill("Bash", null);
        for (Skill skill : List.of(vim, terraform, java, ansible, bash)) {
            personSkillRepository.save(new PersonSkill(person, skill, Proficiency.INTERMEDIATE));
        }
        // Another person's assignment must not appear in this person's collection.
        personSkillRepository.save(new PersonSkill(other, java, Proficiency.EXPERT));
        personSkillRepository.flush();
        entityManager.clear();

        List<PersonSkill> assignments =
                personSkillRepository.findByPersonIdOrdered(person.getId());

        assertThat(assignments).extracting(PersonSkill::getName)
                .containsExactly("Java", "Ansible", "Terraform", "Bash", "Vim");
        assertThat(assignments).extracting(PersonSkill::getCategory)
                .containsExactly("Backend", "Ops", "Ops", null, null);
    }
}
