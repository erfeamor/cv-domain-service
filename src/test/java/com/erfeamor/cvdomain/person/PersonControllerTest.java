package com.erfeamor.cvdomain.person;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PersonController.class)
@AutoConfigureMockMvc(addFilters = false)
class PersonControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PersonRepository personRepository;

    @Test
    void listsAllPeople() throws Exception {
        given(personRepository.findAll())
                .willReturn(List.of(new Person("Jane Doe", "Engineer", "jane@example.com", "Remote", "Bio")));

        mockMvc.perform(get("/api/v1/people"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fullName").value("Jane Doe"));
    }

    @Test
    void createsAPerson() throws Exception {
        Person toSave = new Person("Jane Doe", "Engineer", "jane@example.com", "Remote", "Bio");
        given(personRepository.save(any(Person.class))).willReturn(toSave);

        mockMvc.perform(post("/api/v1/people")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Jane Doe","headline":"Engineer","email":"jane@example.com","location":"Remote","summary":"Bio"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("jane@example.com"));
    }
    /**
     * T-107: a client-supplied id in a POST body must never reach the repository.
     *
     * <p>{@code Person.id} is a private field with no setter, but Jackson's
     * INFER_PROPERTY_MUTATORS binds it anyway, and a non-null id makes Spring Data's
     * {@code save()} take {@code merge()} instead of {@code persist()} — so the row with that id
     * is OVERWRITTEN with the request body and returned as 201.
     *
     * <p>Person is not person-scoped, so there is no cross-person dimension as there is for the
     * child resources; the damage is an unauthorised overwrite of an arbitrary person record
     * (name, headline, email, location, summary) by a caller who only asked to create one.
     */
    @Test
    void rejectsAClientSuppliedIdInsteadOfOverwritingAnExistingPerson() throws Exception {
        mockMvc.perform(post("/api/v1/people")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":999,\"fullName\":\"Mallory\",\"title\":\"Engineer\","
                                + "\"email\":\"mallory@example.com\"}"))
                .andExpect(status().isBadRequest());
        verify(personRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- T-113, contract rule 8
    // Slice-level pairing for OptimisticConcurrencyTest, which covers the same paths end to end.

    private static final String PERSON_BODY = """
            {"fullName":"Jane Doe","headline":"Engineer","email":"jane@example.com",
             "location":"Remote","summary":"Bio"}
            """;

    private static String withVersion(String json, long version) {
        return "{\"version\":" + version + "," + json.strip().substring(1);
    }

    @Test
    void t113StaleVersionIsA409ProblemAndNothingIsSaved() throws Exception {
        Person stored = new Person("Jane Doe", "Engineer", "jane@example.com", "Remote", "Bio");
        ReflectionTestUtils.setField(stored, "id", 5L);
        ReflectionTestUtils.setField(stored, "version", 2L);
        given(personRepository.findById(5L)).willReturn(Optional.of(stored));

        mockMvc.perform(put("/api/v1/people/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withVersion(PERSON_BODY, 1)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409));

        verify(personRepository, never()).save(any());
    }

    @Test
    void t113LostRaceIs409IfTheRowStillExistsAnd404IfItIsGone() throws Exception {
        Person stored = new Person("Jane Doe", "Engineer", "jane@example.com", "Remote", "Bio");
        ReflectionTestUtils.setField(stored, "id", 5L);
        ReflectionTestUtils.setField(stored, "version", 0L);
        given(personRepository.findById(5L)).willReturn(Optional.of(stored));
        given(personRepository.save(any(Person.class)))
                .willThrow(new ObjectOptimisticLockingFailureException(Person.class, 5L));

        given(personRepository.existsById(5L)).willReturn(true);
        mockMvc.perform(put("/api/v1/people/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withVersion(PERSON_BODY, 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        given(personRepository.existsById(5L)).willReturn(false);
        mockMvc.perform(put("/api/v1/people/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withVersion(PERSON_BODY, 0)))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Person 5 not found"));
    }

    @Test
    void t113PostDiscardsAClientSuppliedVersion() throws Exception {
        given(personRepository.save(any(Person.class))).willAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/api/v1/people")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withVersion(PERSON_BODY, 7)))
                .andExpect(status().isCreated());

        verify(personRepository).save(argThat(saved -> saved.getVersion() == null));
    }
}
