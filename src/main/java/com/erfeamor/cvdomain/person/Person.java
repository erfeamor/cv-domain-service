package com.erfeamor.cvdomain.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.annotations.ColumnDefault;

@Entity
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Optimistic-lock version, contract design rule 8 (T-113); the {@code version} column added
     * by cv-database's V2 migration ({@code BIGINT NOT NULL DEFAULT 0}). Starts at 0 on insert and
     * is incremented on every successful PUT. Serialized in every response; bound from a PUT body
     * only so the controller can compare it, never written from the request.
     */
    @Version
    @Column(nullable = false)
    @ColumnDefault("0") // DDL only (the H2 test schema); V2 declares the same default in MySQL.
    private Long version;

    @NotBlank
    @Column(nullable = false)
    private String fullName;

    private String headline;

    @NotBlank
    @Email
    @Column(nullable = false, unique = true)
    private String email;

    private String location;

    private String summary;

    protected Person() {
    }

    public Person(String fullName, String headline, String email, String location, String summary) {
        this.fullName = fullName;
        this.headline = headline;
        this.email = email;
        this.location = location;
        this.summary = summary;
    }

    public Long getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    /**
     * Drops a {@code version} bound from a POST body. Clients never set it on create (rule 8),
     * and a non-null version would also make Spring Data's {@code save()} treat the entity as
     * existing and {@code merge()} it instead of persisting it.
     */
    void discardClientVersion() {
        this.version = null;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getHeadline() {
        return headline;
    }

    public void setHeadline(String headline) {
        this.headline = headline;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }
}
