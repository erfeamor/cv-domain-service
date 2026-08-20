package com.erfeamor.cvdomain.personskill;

import com.erfeamor.cvdomain.person.Person;
import com.erfeamor.cvdomain.skill.Skill;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * A person's assignment of a catalog {@link Skill} at some {@link Proficiency}.
 *
 * <p>The person-scoped half of § Skills, and a different resource from the catalog itself: it has
 * its own 404 semantics (unknown person, unknown skill, or no linking row) and never creates a
 * catalog entry.
 *
 * <p>Mapped onto V1's {@code person_skill}, which has the composite primary key {@code (person_id,
 * skill_id)} and <strong>no {@code id} column</strong>. Both associations are {@code @MapsId} onto
 * {@link PersonSkillId}, so the key is derived from the two owning rows rather than duplicated,
 * and both carry {@code @OnDelete(CASCADE)} to match V1's {@code ON DELETE CASCADE} FKs.
 *
 * <p><strong>The JSON shape is the joined view the contract specifies</strong> — {@code {skillId,
 * name, category, proficiency}} — for both GET and PUT. The identity, the owning person and the
 * skill entity itself are all {@code @JsonIgnore}d, so no {@code personId}, no {@code id} and no
 * nested skill object can leak; {@code name} and {@code category} are read through from the
 * catalog. {@code skill} is {@code EAGER} because every response needs those two fields and the
 * list query {@code JOIN FETCH}es it — a LAZY proxy would either N+1 or fail to serialize once the
 * transaction has closed.
 */
@Entity
@Table(name = "person_skill")
@JsonPropertyOrder({"skillId", "name", "category", "proficiency"})
public class PersonSkill {

    @JsonIgnore
    @EmbeddedId
    private PersonSkillId id;

    @JsonIgnore
    @MapsId("personId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "person_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Person person;

    @JsonIgnore
    @MapsId("skillId")
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "skill_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Skill skill;

    /**
     * Required on the API even though V1's column carries {@code DEFAULT 'INTERMEDIATE'}.
     *
     * <p>The default is unreachable from here: Hibernate always writes an explicit value on
     * INSERT, including an explicit {@code NULL}, so it never falls back to the DDL default.
     * Validation is therefore the entity's responsibility — {@code @NotNull} turns a body missing
     * {@code proficiency} into a 400 rather than a constraint-violation 500, which is what design
     * rule 4 asks for. Deliberately NOT defaulted in Java either: silently inventing
     * INTERMEDIATE for a malformed request would hide the client's mistake.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Proficiency proficiency;

    protected PersonSkill() {
    }

    public PersonSkill(Person person, Skill skill, Proficiency proficiency) {
        this.person = person;
        this.skill = skill;
        // Populated up front rather than left to @MapsId's flush-time derivation, so that a
        // freshly constructed instance is already identified by its pair: Spring Data's save()
        // then sees a non-new entity and merges over the existing row (the upsert), instead of
        // attempting a second INSERT that the primary key would reject.
        this.id = new PersonSkillId(person.getId(), skill.getId());
        this.proficiency = proficiency;
    }

    @JsonIgnore
    public PersonSkillId getId() {
        return id;
    }

    @JsonIgnore
    public Person getPerson() {
        return person;
    }

    @JsonIgnore
    public Skill getSkill() {
        return skill;
    }

    /** The contract's identifier for an assignment: the catalog skill's id, never a row id. */
    public Long getSkillId() {
        return id == null ? null : id.getSkillId();
    }

    /** Read through from the catalog — the joined shape GET and PUT both return. */
    public String getName() {
        return skill == null ? null : skill.getName();
    }

    /** Read through from the catalog; null means uncategorized, which sorts last. */
    public String getCategory() {
        return skill == null ? null : skill.getCategory();
    }

    public Proficiency getProficiency() {
        return proficiency;
    }

    public void setProficiency(Proficiency proficiency) {
        this.proficiency = proficiency;
    }
}
