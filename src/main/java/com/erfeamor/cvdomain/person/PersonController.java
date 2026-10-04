package com.erfeamor.cvdomain.person;

import com.erfeamor.cvdomain.common.ClientSuppliedIds;
import com.erfeamor.cvdomain.common.ConcurrentUpdateException;
import com.erfeamor.cvdomain.common.StaleVersionException;
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

@RestController
@RequestMapping("/api/v1/people")
public class PersonController {

    private final PersonRepository personRepository;

    public PersonController(PersonRepository personRepository) {
        this.personRepository = personRepository;
    }

    @GetMapping
    public List<Person> findAll() {
        return personRepository.findAll();
    }

    @GetMapping("/{id}")
    public Person findById(@PathVariable Long id) {
        return personRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Person " + id + " not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Person create(@Valid @RequestBody Person person) {
        // T-107: a bound id turns save() into merge() and overwrites that row instead of
        // creating one. See ClientSuppliedIds for why this is a 400 rather than a silent ignore.
        ClientSuppliedIds.reject(person.getId());
        person.discardClientVersion();
        return personRepository.save(person);
    }

    /**
     * Replaces the fields of an existing person, keeping its id: the same shape as the section
     * updates (see {@code ExperienceController#update} for the full reasoning).
     *
     * <p>T-113 made this {@code @Transactional}. Rule 8's explicit version comparison needs the
     * row it compares against to be the row it writes; without a transaction the read and the
     * {@code save()} were separate, the write was a {@code merge()} of a detached entity, and a
     * person deleted in between was re-inserted under a new id with a {@code 200} (T-108's defect,
     * which T-108 fixed only for the sections).
     */
    @PutMapping("/{id}")
    @Transactional
    public Person update(@PathVariable Long id, @Valid @RequestBody Person update) {
        Person existing = findById(id);
        Long readVersion = existing.getVersion();
        StaleVersionException.requireCurrent(update.getVersion(), readVersion);
        existing.setFullName(update.getFullName());
        existing.setHeadline(update.getHeadline());
        existing.setEmail(update.getEmail());
        existing.setLocation(update.getLocation());
        existing.setSummary(update.getSummary());
        try {
            Person saved = personRepository.save(existing);
            personRepository.flush();
            if (Objects.equals(saved.getVersion(), readVersion)) {
                // Nothing was dirty, so no UPDATE was issued: bump the version anyway.
                personRepository.forceVersionIncrement(saved);
            }
            return saved;
        } catch (ObjectOptimisticLockingFailureException staleSinceRead) {
            throw new ConcurrentUpdateException(id, staleSinceRead);
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        personRepository.deleteById(id);
    }

    /** See {@link #update}: runs after the update's transaction has rolled back. */
    @ExceptionHandler(ConcurrentUpdateException.class)
    public ResponseEntity<?> handleConcurrentUpdate(ConcurrentUpdateException ex) {
        if (personRepository.existsById(ex.getId())) {
            return StaleVersionException.response();
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body("Person " + ex.getId() + " not found");
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<String> handleNotFound(EntityNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }
}
