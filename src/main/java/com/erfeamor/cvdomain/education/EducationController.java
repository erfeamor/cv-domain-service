package com.erfeamor.cvdomain.education;

import com.erfeamor.cvdomain.common.ClientSuppliedIds;
import com.erfeamor.cvdomain.person.Person;
import com.erfeamor.cvdomain.person.PersonRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
        education.setPerson(requirePerson(personId));
        return educationRepository.save(education);
    }

    @PutMapping("/{id}")
    public Education update(@PathVariable Long personId, @PathVariable Long id,
            @Valid @RequestBody Education update) {
        requirePerson(personId);
        Education existing = requireEducation(personId, id);
        existing.setInstitution(update.getInstitution());
        existing.setDegree(update.getDegree());
        existing.setFieldOfStudy(update.getFieldOfStudy());
        existing.setStartDate(update.getStartDate());
        existing.setEndDate(update.getEndDate());
        return educationRepository.save(existing);
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

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<String> handleNotFound(EntityNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }
}
