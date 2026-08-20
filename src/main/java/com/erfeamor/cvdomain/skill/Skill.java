package com.erfeamor.cvdomain.skill;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A catalog skill — the global half of § Skills, deliberately NOT person-scoped.
 *
 * <p>Mapped onto the {@code skill} table created by cv-database's V1 migration ({@code name
 * VARCHAR(100) NOT NULL UNIQUE}, {@code category VARCHAR(100)} nullable); production runs {@code
 * ddl-auto: validate}, so the column names and types here must stay identical to that migration.
 * The entity is bound directly by the controller (no DTO layer) — the same accepted trade-off as
 * {@code person/}, {@code experience/} and {@code education/} — so the JSON shape is the contract
 * shape {@code {id, name, category}}.
 */
@Entity
public class Skill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Required (DoR 3: inferred from V1's {@code NOT NULL}), and unique at the database level —
     * the uniqueness is enforced by the constraint, never by a read-before-write, because a
     * pre-check loses the race between two concurrent POSTs. {@code @Size} mirrors V1's
     * {@code VARCHAR(100)}: {@code ddl-auto: validate} does not check length and H2 would create
     * a {@code varchar(255)}, so without it an over-long name passes every test and then fails
     * against real MySQL in strict mode as a 500 where design rule 4 requires a 400.
     */
    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, unique = true)
    private String name;

    /** Optional (DoR 3: V1 leaves the column nullable). Null means "uncategorized". */
    @Size(max = 100)
    @Column(length = 100)
    private String category;

    protected Skill() {
    }

    public Skill(String name, String category) {
        this.name = name;
        this.category = category;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }
}
