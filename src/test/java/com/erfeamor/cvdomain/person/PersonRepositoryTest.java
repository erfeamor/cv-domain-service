package com.erfeamor.cvdomain.person;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class PersonRepositoryTest {

    @org.springframework.beans.factory.annotation.Autowired
    private PersonRepository personRepository;

    @Test
    void savesAndReloadsAPerson() {
        Person saved = personRepository.save(
                new Person("Jane Doe", "Full-Stack Engineer", "jane@example.com", "Remote", "Demo summary"));

        Person found = personRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getFullName()).isEqualTo("Jane Doe");
        assertThat(found.getEmail()).isEqualTo("jane@example.com");
    }
}
