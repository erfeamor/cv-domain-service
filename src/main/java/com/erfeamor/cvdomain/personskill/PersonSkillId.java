package com.erfeamor.cvdomain.personskill;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * The composite primary key of {@code person_skill} — {@code (person_id, skill_id)}.
 *
 * <p>V1 gives this table no surrogate {@code id} column, so the key IS the pair. {@code equals}
 * and {@code hashCode} are load-bearing, not boilerplate: JPA looks entities up in the persistence
 * context by key equality, so an identity-based (inherited) {@code equals} makes {@code
 * findById(new PersonSkillId(1L, 5L))} return empty for a row that plainly exists — a silent
 * failure that no compiler catches. {@code PersonSkillRepositoryTest.p1} constructs a fresh id
 * instance precisely to hold that line.
 */
@Embeddable
public class PersonSkillId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "person_id")
    private Long personId;

    @Column(name = "skill_id")
    private Long skillId;

    protected PersonSkillId() {
    }

    public PersonSkillId(Long personId, Long skillId) {
        this.personId = personId;
        this.skillId = skillId;
    }

    public Long getPersonId() {
        return personId;
    }

    public Long getSkillId() {
        return skillId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PersonSkillId)) {
            return false;
        }
        PersonSkillId that = (PersonSkillId) other;
        return Objects.equals(personId, that.personId) && Objects.equals(skillId, that.skillId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(personId, skillId);
    }

    @Override
    public String toString() {
        return "PersonSkillId[personId=" + personId + ", skillId=" + skillId + "]";
    }
}
