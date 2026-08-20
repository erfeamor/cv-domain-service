package com.erfeamor.cvdomain.person;

import com.erfeamor.cvdomain.common.ClientSuppliedIds;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
        return personRepository.save(person);
    }

    @PutMapping("/{id}")
    public Person update(@PathVariable Long id, @Valid @RequestBody Person update) {
        Person existing = findById(id);
        existing.setFullName(update.getFullName());
        existing.setHeadline(update.getHeadline());
        existing.setEmail(update.getEmail());
        existing.setLocation(update.getLocation());
        existing.setSummary(update.getSummary());
        return personRepository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        personRepository.deleteById(id);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<String> handleNotFound(EntityNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }
}
