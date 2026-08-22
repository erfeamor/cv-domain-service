package com.erfeamor.cvdomain.project;

import com.erfeamor.cvdomain.person.Person;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * A portfolio project, always owned by a {@link Person}.
 *
 * <p>Mapped onto the {@code project} table created by cv-database's V1 migration; the column names
 * here must stay identical to that migration because production runs {@code ddl-auto: validate}.
 * The entity is bound directly by the controller (no DTO layer) — the same accepted trade-off as
 * {@code person/}, {@code experience/} and {@code education/} — so the JSON shape is the contract
 * shape: the owning person is {@link JsonIgnore}d and never leaks a {@code personId}.
 *
 * <p><strong>Required: {@code name} only.</strong> docs/api-contract.md § Projects lists no other
 * required field, and this resource is the one place where that genuinely differs from its
 * siblings: {@code start_date} and {@code end_date} are both nullable in V1, unlike
 * {@code experience} and {@code education} where {@code start_date} is NOT NULL. Adding
 * {@code @NotNull} to {@code startDate} — or {@code @NotBlank} to {@code description} — by analogy
 * with those two would narrow the contract, so the four optional fields deliberately carry no
 * validation at all.
 */
@Entity
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "person_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Person person;

    /**
     * The only required field. {@code @Size} mirrors V1's {@code VARCHAR(150)} deliberately:
     * {@code ddl-auto: validate} does NOT check column length and H2 creates a
     * {@code varchar(255)} from an unannotated mapping, so without it a 160-character name passes
     * every test and then fails against real MySQL in strict mode with error 1406 — surfacing as a
     * 500 where contract design rule 4 requires a 400.
     */
    @NotBlank
    @Size(max = 150)
    @Column(nullable = false)
    private String name;

    /** Optional; V1 declares it {@code TEXT}, so no length constraint applies. */
    private String description;

    /**
     * Optional, and the highest-risk mapping in this aggregate: the column is {@code repo_url}
     * while the contract payload is {@code repoUrl}. Spelled out explicitly rather than left to
     * the naming strategy, because H2 tolerates a mismatch that MySQL's {@code ddl-auto: validate}
     * would reject at boot.
     *
     * <p>No {@code @URL} or {@code @Pattern}: the contract states only "Required: {@code name}"
     * and imposes no format anywhere, so a format constraint would narrow the contract without a
     * contract change.
     */
    @Column(name = "repo_url")
    private String repoUrl;

    /**
     * Optional — nullable in V1, unlike the other two sections' start dates. An undated project is
     * a legitimate row and sorts <em>last</em> (contract § Ordering).
     */
    @Column(name = "start_date")
    private LocalDate startDate;

    /** Null means "ongoing" — a plain SQL NULL, no sentinel date. */
    @Column(name = "end_date")
    private LocalDate endDate;

    protected Project() {
    }

    public Project(Person person, String name, String description, String repoUrl,
            LocalDate startDate, LocalDate endDate) {
        this.person = person;
        this.name = name;
        this.description = description;
        this.repoUrl = repoUrl;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public Long getId() {
        return id;
    }

    @JsonIgnore
    public Person getPerson() {
        return person;
    }

    public void setPerson(Person person) {
        this.person = person;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRepoUrl() {
        return repoUrl;
    }

    public void setRepoUrl(String repoUrl) {
        this.repoUrl = repoUrl;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }
}
