package com.erfeamor.cvdomain.education;

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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * An education history entry, always owned by a {@link Person}.
 *
 * <p>Mapped onto the {@code education} table created by cv-database's V1 migration; the column
 * names here must stay identical to that migration because production runs {@code ddl-auto:
 * validate}. The entity is bound directly by the controller (no DTO layer) — the same accepted
 * trade-off as {@code person/} and {@code experience/} — so the JSON shape is the contract shape:
 * the owning person is {@link JsonIgnore}d and never leaks a {@code personId} into the payload.
 */
@Entity
public class Education {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "person_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Person person;

    /**
     * {@code @Size} mirrors V1's {@code VARCHAR(150)} deliberately. {@code ddl-auto: validate}
     * does NOT check column length and H2 creates a {@code varchar(255)} from an unannotated
     * mapping, so without this a 160-character value passes every test and then fails against
     * real MySQL in strict mode with error 1406 — surfacing as a 500 where contract design rule 4
     * requires a 400.
     */
    @NotBlank
    @Size(max = 150)
    @Column(nullable = false)
    private String institution;

    @NotBlank
    @Size(max = 150)
    @Column(nullable = false)
    private String degree;

    /**
     * Optional, and the highest-risk mapping in this aggregate: the column is {@code
     * field_of_study} while the contract payload is {@code fieldOfStudy}. Spelled out explicitly
     * rather than left to the naming strategy, because H2 tolerates a mismatch that MySQL's
     * {@code ddl-auto: validate} would reject at boot.
     */
    @Size(max = 150)
    @Column(name = "field_of_study")
    private String fieldOfStudy;

    @NotNull
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** Null means "ongoing" — a plain SQL NULL, no sentinel date. */
    @Column(name = "end_date")
    private LocalDate endDate;

    protected Education() {
    }

    public Education(Person person, String institution, String degree, String fieldOfStudy,
            LocalDate startDate, LocalDate endDate) {
        this.person = person;
        this.institution = institution;
        this.degree = degree;
        this.fieldOfStudy = fieldOfStudy;
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

    public String getInstitution() {
        return institution;
    }

    public void setInstitution(String institution) {
        this.institution = institution;
    }

    public String getDegree() {
        return degree;
    }

    public void setDegree(String degree) {
        this.degree = degree;
    }

    public String getFieldOfStudy() {
        return fieldOfStudy;
    }

    public void setFieldOfStudy(String fieldOfStudy) {
        this.fieldOfStudy = fieldOfStudy;
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
