package com.erfeamor.cvdomain.experience;

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
import java.time.LocalDate;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * A work experience entry, always owned by a {@link Person}.
 *
 * <p>Mapped onto the {@code experience} table created by cv-database's V1 migration; the column
 * names here must stay identical to that migration because production runs {@code ddl-auto:
 * validate}. The entity is bound directly by the controller (no DTO layer) — the same accepted
 * trade-off as {@code person/} — so the JSON shape is the contract shape: the owning person is
 * {@link JsonIgnore}d and never leaks a {@code personId} into the payload.
 */
@Entity
public class Experience {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "person_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Person person;

    @NotBlank
    @Column(nullable = false)
    private String company;

    // "role" is keyword-adjacent in MySQL but not reserved; the column name comes from V1 and must
    // not be renamed.
    @NotBlank
    @Column(name = "role", nullable = false)
    private String role;

    private String location;

    @NotNull
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** Null means "current" — a plain SQL NULL, no sentinel date. */
    @Column(name = "end_date")
    private LocalDate endDate;

    private String description;

    protected Experience() {
    }

    public Experience(Person person, String company, String role, String location,
            LocalDate startDate, LocalDate endDate, String description) {
        this.person = person;
        this.company = company;
        this.role = role;
        this.location = location;
        this.startDate = startDate;
        this.endDate = endDate;
        this.description = description;
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

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
