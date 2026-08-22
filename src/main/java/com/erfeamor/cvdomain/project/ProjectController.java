package com.erfeamor.cvdomain.project;

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
 * Person-scoped CRUD for portfolio projects, per docs/api-contract.md § Projects.
 */
@RestController
@RequestMapping("/api/v1/people/{personId}/projects")
public class ProjectController {

    /**
     * One message for every 404 this resource can produce — an unknown person, an unknown project
     * and a project owned by someone else all look identical to the client, so the body never
     * discloses which id missed.
     */
    private static final String NOT_FOUND_MESSAGE = "Project not found";

    private final ProjectRepository projectRepository;
    private final PersonRepository personRepository;

    public ProjectController(ProjectRepository projectRepository,
            PersonRepository personRepository) {
        this.projectRepository = projectRepository;
        this.personRepository = personRepository;
    }

    @GetMapping
    public List<Project> findAll(@PathVariable Long personId) {
        requirePerson(personId);
        // An existing person with no rows is an empty collection, not a 404.
        return projectRepository.findByPersonIdOrdered(personId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Project create(@PathVariable Long personId, @Valid @RequestBody Project project) {
        ClientSuppliedIds.reject(project.getId());
        project.setPerson(requirePerson(personId));
        return projectRepository.save(project);
    }

    @PutMapping("/{id}")
    public Project update(@PathVariable Long personId, @PathVariable Long id,
            @Valid @RequestBody Project update) {
        requirePerson(personId);
        Project existing = requireProject(personId, id);
        existing.setName(update.getName());
        existing.setDescription(update.getDescription());
        existing.setRepoUrl(update.getRepoUrl());
        existing.setStartDate(update.getStartDate());
        existing.setEndDate(update.getEndDate());
        return projectRepository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long personId, @PathVariable Long id) {
        requirePerson(personId);
        // Deleting an id that does not exist under this person is a 404, not a silent 204.
        projectRepository.delete(requireProject(personId, id));
    }

    /** The person is resolved before any child lookup, so an unknown person always wins the 404. */
    private Person requirePerson(Long personId) {
        return personRepository.findById(personId)
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_MESSAGE));
    }

    private Project requireProject(Long personId, Long id) {
        return projectRepository.findByIdAndPersonId(id, personId)
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_MESSAGE));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<String> handleNotFound(EntityNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }
}
