package com.erfeamor.cvdomain.education;

import com.erfeamor.cvdomain.common.ClientSuppliedIds;
import com.erfeamor.cvdomain.common.ConcurrentUpdateException;
import com.erfeamor.cvdomain.common.StaleVersionException;
import com.erfeamor.cvdomain.common.VersionBumping;
import com.erfeamor.cvdomain.person.Person;
import com.erfeamor.cvdomain.person.PersonRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Person-scoped CRUD for education history, per docs/api-contract.md § Education.
 *
 * <p>The plural in {@code /educations} is the contract's choice and is kept deliberately.
 */
@RestController
@RequestMapping("/api/v1/people/{personId}/educations")
public class EducationController {

    /**
     * One message for every 404 this resource can produce — an unknown person, an unknown
     * education entry and an entry owned by someone else all look identical to the client, so the
     * body never discloses which id missed.
     */
    private static final String NOT_FOUND_MESSAGE = "Education not found";

    private final EducationRepository educationRepository;
    private final PersonRepository personRepository;

    public EducationController(EducationRepository educationRepository,
            PersonRepository personRepository) {
        this.educationRepository = educationRepository;
        this.personRepository = personRepository;
    }

    @GetMapping
    public List<Education> findAll(@PathVariable Long personId) {
        requirePerson(personId);
        // An existing person with no rows is an empty collection, not a 404.
        return educationRepository.findByPersonIdOrderByStartDateDescIdAsc(personId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Education create(@PathVariable Long personId, @Valid @RequestBody Education education) {
        ClientSuppliedIds.reject(education.getId());
        education.discardClientVersion();
        education.setPerson(requirePerson(personId));
        return educationRepository.save(education);
    }

    /**
     * Replaces the fields of an existing row, keeping its id (T-108).
     *
     * <p><strong>One transaction, read to write.</strong> With {@code open-in-view: false} and no
     * annotation, the lookup and the {@code save()} ran as separate transactions, so the write
     * was a {@code merge()} of a detached entity. Here the row read by {@link #requireEducation} stays
     * managed and the write is a plain dirty-checked UPDATE of that same row.
     *
     * <p><strong>Optimistic concurrency, contract rule 8 (T-113).</strong> A {@code version} in the
     * body that differs from the row's is a {@code 409}, checked explicitly against the row read
     * here ({@link StaleVersionException#requireCurrent}); an omitted one applies unconditionally.
     * Every successful PUT increments the version, a no-op one included ({@link VersionBumping}).
     *
     * <p><strong>A row changed or deleted between the read and the write.</strong> The UPDATE is
     * conditional on the version read, so it then matches 0 rows and Hibernate raises a
     * stale-state failure. It is flushed explicitly here, rather than left to the commit that runs
     * after this method returns, so it surfaces inside the method and is wrapped right here, only
     * for this call, as a {@link ConcurrentUpdateException}. Its handler below resolves it after
     * the rollback: row gone is T-108's {@code 404}, row still there is a {@code 409}.
     */
    @PutMapping("/{id}")
    @Transactional
    public Education update(@PathVariable Long personId, @PathVariable Long id,
            @Valid @RequestBody Education update) {
        requirePerson(personId);
        Education existing = requireEducation(personId, id);
        Long readVersion = existing.getVersion();
        StaleVersionException.requireCurrent(update.getVersion(), readVersion);
        existing.setInstitution(update.getInstitution());
        existing.setDegree(update.getDegree());
        existing.setFieldOfStudy(update.getFieldOfStudy());
        existing.setStartDate(update.getStartDate());
        existing.setEndDate(update.getEndDate());
        try {
            Education saved = educationRepository.save(existing);
            educationRepository.flush();
            if (Objects.equals(saved.getVersion(), readVersion)) {
                // Nothing was dirty, so no UPDATE was issued: bump the version anyway.
                educationRepository.forceVersionIncrement(saved);
            }
            return saved;
        } catch (ObjectOptimisticLockingFailureException staleSinceRead) {
            throw new ConcurrentUpdateException(id, staleSinceRead);
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long personId, @PathVariable Long id) {
        requirePerson(personId);
        // Deleting an id that does not exist under this person is a 404, not a silent 204.
        educationRepository.delete(requireEducation(personId, id));
    }

    /** The person is resolved before any child lookup, so an unknown person always wins the 404. */
    private Person requirePerson(Long personId) {
        return personRepository.findById(personId)
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_MESSAGE));
    }

    private Education requireEducation(Long personId, Long id) {
        return educationRepository.findByIdAndPersonId(id, personId)
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_MESSAGE));
    }

    /** See {@link #update}: runs after the update's transaction has rolled back. */
    @ExceptionHandler(ConcurrentUpdateException.class)
    public ResponseEntity<?> handleConcurrentUpdate(ConcurrentUpdateException ex) {
        if (educationRepository.existsById(ex.getId())) {
            return StaleVersionException.response();
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND_MESSAGE);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<String> handleNotFound(EntityNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }
}
