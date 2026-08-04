package com.erfeamor.cvdomain.experience;

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
 * Person-scoped CRUD for work experience, per docs/api-contract.md § Experience.
 */
@RestController
@RequestMapping("/api/v1/people/{personId}/experiences")
public class ExperienceController {

    /**
     * One message for every 404 this resource can produce — an unknown person, an unknown
     * experience and an experience owned by someone else all look identical to the client, so the
     * body never discloses which id missed.
     */
    private static final String NOT_FOUND_MESSAGE = "Experience not found";

    private final ExperienceRepository experienceRepository;
    private final PersonRepository personRepository;

    public ExperienceController(ExperienceRepository experienceRepository,
            PersonRepository personRepository) {
        this.experienceRepository = experienceRepository;
        this.personRepository = personRepository;
    }

    @GetMapping
    public List<Experience> findAll(@PathVariable Long personId) {
        requirePerson(personId);
        // An existing person with no rows is an empty collection, not a 404.
        return experienceRepository.findByPersonId(personId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Experience create(@PathVariable Long personId,
            @Valid @RequestBody Experience experience) {
        experience.setPerson(requirePerson(personId));
        return experienceRepository.save(experience);
    }

    @PutMapping("/{id}")
    public Experience update(@PathVariable Long personId, @PathVariable Long id,
            @Valid @RequestBody Experience update) {
        requirePerson(personId);
        Experience existing = requireExperience(personId, id);
        existing.setCompany(update.getCompany());
        existing.setRole(update.getRole());
        existing.setLocation(update.getLocation());
        existing.setStartDate(update.getStartDate());
        existing.setEndDate(update.getEndDate());
        existing.setDescription(update.getDescription());
        return experienceRepository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long personId, @PathVariable Long id) {
        requirePerson(personId);
        // Deleting an id that does not exist under this person is a 404, not a silent 204.
        experienceRepository.delete(requireExperience(personId, id));
    }

    /** The person is resolved before any child lookup, so an unknown person always wins the 404. */
    private Person requirePerson(Long personId) {
        return personRepository.findById(personId)
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_MESSAGE));
    }

    private Experience requireExperience(Long personId, Long id) {
        return experienceRepository.findByIdAndPersonId(id, personId)
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_MESSAGE));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<String> handleNotFound(EntityNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }
}
