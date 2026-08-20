package com.erfeamor.cvdomain.experience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erfeamor.cvdomain.person.Person;
import com.erfeamor.cvdomain.person.PersonRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-level coverage for the person-scoped experience resource (test-plan cases C1-C17).
 */
@WebMvcTest(ExperienceController.class)
@AutoConfigureMockMvc(addFilters = false)
class ExperienceControllerTest {

    private static final String VALID_BODY = """
            {"company":"ACME","role":"Backend Engineer","location":"Remote",
             "startDate":"2022-01-01","endDate":null,"description":"Built things"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ExperienceRepository experienceRepository;

    @MockBean
    private PersonRepository personRepository;

    private static Person person() {
        return new Person("Jane Doe", "Engineer", "jane@example.com", "Remote", "Bio");
    }

    private static Experience experience(Long id, String company) {
        Experience experience = new Experience(person(), company, "Backend Engineer", "Remote",
                LocalDate.of(2022, 1, 1), null, "Built things");
        return withId(experience, id);
    }

    private static Experience withId(Experience experience, Long id) {
        ReflectionTestUtils.setField(experience, "id", id);
        return experience;
    }

    private void givenPersonExists(long personId) {
        given(personRepository.findById(personId)).willReturn(Optional.of(person()));
    }

    private void givenPersonMissing(long personId) {
        given(personRepository.findById(personId)).willReturn(Optional.empty());
    }

    private void givenSaveReturnsWithId(long id) {
        given(experienceRepository.save(any(Experience.class)))
                .willAnswer(invocation -> withId(invocation.getArgument(0), id));
    }

    /** C1: GET collection for an existing person returns the person's rows. */
    @Test
    void c1ListsTheExperiencesOfAnExistingPerson() throws Exception {
        givenPersonExists(1L);
        given(experienceRepository.findByPersonId(1L))
                .willReturn(List.of(experience(5L, "ACME"), experience(6L, "Globex")));

        mockMvc.perform(get("/api/v1/people/1/experiences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].company").value("ACME"))
                .andExpect(jsonPath("$[1].company").value("Globex"));
    }

    /** C2 (DoR 3): an existing person with no rows is 200 [], never 404. */
    @Test
    void c2ReturnsAnEmptyArrayWhenThePersonHasNoExperiences() throws Exception {
        givenPersonExists(1L);
        given(experienceRepository.findByPersonId(1L)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/people/1/experiences"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]", true));
    }

    /** C3: POST a valid body with an explicit null endDate. */
    @Test
    void c3CreatesAnExperienceAndKeepsANullEndDate() throws Exception {
        givenPersonExists(1L);
        givenSaveReturnsWithId(5L);

        mockMvc.perform(post("/api/v1/people/1/experiences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.endDate", nullValue()))
                .andExpect(content().string(containsString("\"endDate\":null")));
    }

    /** C4: company is required. */
    @Test
    void c4RejectsAMissingCompany() throws Exception {
        givenPersonExists(1L);

        mockMvc.perform(post("/api/v1/people/1/experiences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"Backend Engineer","startDate":"2022-01-01"}
                                """))
                .andExpect(status().isBadRequest());

        verify(experienceRepository, never()).save(any());
    }

    /** C5: role is required. */
    @Test
    void c5RejectsAMissingRole() throws Exception {
        givenPersonExists(1L);

        mockMvc.perform(post("/api/v1/people/1/experiences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"ACME","startDate":"2022-01-01"}
                                """))
                .andExpect(status().isBadRequest());

        verify(experienceRepository, never()).save(any());
    }

    /** C6: startDate is required. */
    @Test
    void c6RejectsAMissingStartDate() throws Exception {
        givenPersonExists(1L);

        mockMvc.perform(post("/api/v1/people/1/experiences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"ACME","role":"Backend Engineer"}
                                """))
                .andExpect(status().isBadRequest());

        verify(experienceRepository, never()).save(any());
    }

    /** C7: omitted optionals serialize back as JSON null, not as missing keys. */
    @Test
    void c7OmittedOptionalsSerializeAsNull() throws Exception {
        givenPersonExists(1L);
        givenSaveReturnsWithId(5L);

        mockMvc.perform(post("/api/v1/people/1/experiences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"ACME","role":"Backend Engineer","startDate":"2022-01-01"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.location", nullValue()))
                .andExpect(jsonPath("$.endDate", nullValue()))
                .andExpect(jsonPath("$.description", nullValue()))
                .andExpect(jsonPath("$.size()").value(7));
    }

    /** C8: PUT of an owned row updates it. */
    @Test
    void c8UpdatesAnOwnedExperience() throws Exception {
        givenPersonExists(1L);
        given(experienceRepository.findByIdAndPersonId(5L, 1L))
                .willReturn(Optional.of(experience(5L, "Old Co")));
        givenSaveReturnsWithId(5L);

        mockMvc.perform(put("/api/v1/people/1/experiences/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"ACME","role":"Staff Engineer","location":"Madrid",
                                 "startDate":"2023-02-01","endDate":"2024-03-31","description":"Updated"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.company").value("ACME"))
                .andExpect(jsonPath("$.role").value("Staff Engineer"))
                .andExpect(jsonPath("$.location").value("Madrid"))
                .andExpect(jsonPath("$.startDate").value("2023-02-01"))
                .andExpect(jsonPath("$.endDate").value("2024-03-31"))
                .andExpect(jsonPath("$.description").value("Updated"));
    }

    /** C9: PUT is validated like POST. */
    @Test
    void c9RejectsAnUpdateWithAMissingRequiredField() throws Exception {
        givenPersonExists(1L);

        mockMvc.perform(put("/api/v1/people/1/experiences/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"ACME","startDate":"2022-01-01"}
                                """))
                .andExpect(status().isBadRequest());

        verify(experienceRepository, never()).save(any());
    }

    /** C10: DELETE of an owned row is 204 with an empty body. */
    @Test
    void c10DeletesAnOwnedExperience() throws Exception {
        givenPersonExists(1L);
        Experience owned = experience(5L, "ACME");
        given(experienceRepository.findByIdAndPersonId(5L, 1L)).willReturn(Optional.of(owned));

        mockMvc.perform(delete("/api/v1/people/1/experiences/5"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(experienceRepository).delete(owned);
    }

    /** C11: unknown person on GET. */
    @Test
    void c11ReturnsNotFoundListingForAnUnknownPerson() throws Exception {
        givenPersonMissing(999L);

        mockMvc.perform(get("/api/v1/people/999/experiences"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(experienceRepository);
    }

    /** C12: unknown person on POST. */
    @Test
    void c12ReturnsNotFoundCreatingForAnUnknownPerson() throws Exception {
        givenPersonMissing(999L);

        mockMvc.perform(post("/api/v1/people/999/experiences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNotFound());

        verifyNoInteractions(experienceRepository);
    }

    /** C13 (DoR 4): unknown person on PUT — the person is checked before the child lookup. */
    @Test
    void c13ReturnsNotFoundUpdatingForAnUnknownPerson() throws Exception {
        givenPersonMissing(999L);

        mockMvc.perform(put("/api/v1/people/999/experiences/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNotFound());

        verifyNoInteractions(experienceRepository);
    }

    /** C14 (DoR 4): unknown person on DELETE — the person is checked before the child lookup. */
    @Test
    void c14ReturnsNotFoundDeletingForAnUnknownPerson() throws Exception {
        givenPersonMissing(999L);

        mockMvc.perform(delete("/api/v1/people/999/experiences/5"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(experienceRepository);
    }

    /** C15 (DoR 1): DELETE of an unknown experience id is 404, not 204. */
    @Test
    void c15ReturnsNotFoundDeletingAnUnknownExperience() throws Exception {
        givenPersonExists(1L);
        given(experienceRepository.findByIdAndPersonId(9999L, 1L)).willReturn(Optional.empty());

        mockMvc.perform(delete("/api/v1/people/1/experiences/9999"))
                .andExpect(status().isNotFound());

        verify(experienceRepository, never()).delete(any());
        verify(experienceRepository, never()).deleteById(any());
    }

    /**
     * C16 (DoR 2): an experience owned by person 2 is invisible under person 1 — 404 on PUT and on
     * DELETE, the row is never written or removed, and the lookup goes through the scoped repository
     * method (an unscoped {@code findById} plus a manual owner comparison would be the IDOR).
     */
    @Test
    void c16HidesAnExperienceOwnedByAnotherPerson() throws Exception {
        givenPersonExists(1L);
        given(experienceRepository.findByIdAndPersonId(5L, 1L)).willReturn(Optional.empty());

        mockMvc.perform(put("/api/v1/people/1/experiences/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/people/1/experiences/5"))
                .andExpect(status().isNotFound());

        verify(experienceRepository, never()).save(any());
        verify(experienceRepository, never()).delete(any());
        verify(experienceRepository, never()).deleteById(any());
        verify(experienceRepository, never()).findById(any());
    }

    /**
     * C16 (DoR 4, disclosure): a cross-person id and an unknown id are indistinguishable to the
     * client — same status and same body.
     */
    @Test
    void c16CrossPersonAndUnknownIdsAreIndistinguishable() throws Exception {
        givenPersonExists(1L);
        given(experienceRepository.findByIdAndPersonId(5L, 1L)).willReturn(Optional.empty());
        given(experienceRepository.findByIdAndPersonId(9999L, 1L)).willReturn(Optional.empty());

        String crossPerson = mockMvc.perform(delete("/api/v1/people/1/experiences/5"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String unknownId = mockMvc.perform(delete("/api/v1/people/1/experiences/9999"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(crossPerson).isEqualTo(unknownId);
    }

    /** C17: the response object matches the contract shape exactly — no extra, no missing fields. */
    @Test
    void c17ResponseMatchesTheContractShapeExactly() throws Exception {
        givenPersonExists(1L);
        given(experienceRepository.findByPersonId(1L)).willReturn(List.of(experience(5L, "ACME")));

        mockMvc.perform(get("/api/v1/people/1/experiences"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [{"id":5,"company":"ACME","role":"Backend Engineer","location":"Remote",
                          "startDate":"2022-01-01","endDate":null,"description":"Built things"}]
                        """, true))
                .andExpect(jsonPath("$[0].size()").value(7))
                .andExpect(jsonPath("$[0].personId").doesNotExist())
                .andExpect(jsonPath("$[0].person").doesNotExist());
    }

    /*
     * REPLACED BY T-107. This test used to assert the opposite -- 201, with the client's id
     * "ignored" -- and it passed, which is why nobody looked again for three weeks:
     *
     *     void clientSuppliedIdInThePostBodyIsIgnored() {
     *         givenPersonExists(1L);
     *         givenSaveReturnsWithId(5L);          // <-- the mock manufactured the result
     *         ... .content("{\"id\":999, ...}")
     *             .andExpect(status().isCreated())
     *             .andExpect(jsonPath("$.id").value(5));
     *     }
     *
     * Its comment claimed "the entity exposes no id mutator, so Jackson ignores it". Jackson does
     * NOT ignore it -- INFER_PROPERTY_MUTATORS binds the private field, verified empirically. The
     * test passed because givenSaveReturnsWithId(5L) stubs save() to return an entity with id 5
     * whatever it is handed, so the assertion measured the mock rather than the code. Against a
     * real repository the id would have been 999 and save() would have merged over that row.
     *
     * A green test asserting the safe-looking behaviour is worse than no test: it answers the
     * question before anyone thinks to ask it. Replaced rather than deleted so the shape of the
     * mistake stays visible.
     */

    /**
     * T-107: a client-supplied id in a POST body must never reach the repository.
     *
     * <p>{@code Experience.id} is a private field with no setter, which reads as un-bindable.
     * Jackson's INFER_PROPERTY_MUTATORS binds it regardless, and a non-null id makes Spring
     * Data's {@code save()} take {@code merge()} instead of {@code persist()} — while
     * {@code create} has already set the owning person to the caller's. The resulting UPDATE
     * reassigns ANOTHER PERSON'S ROW to the caller and answers 201 with the victim's id.
     *
     * <p>That is the same cross-person write {@code findByIdAndPersonId} scopes PUT and DELETE
     * against (DoR ruling 2), reaching the one verb with no existing row to scope to.
     */
    @Test
    void rejectsAClientSuppliedIdInsteadOfMergingOverAnExistingRow() throws Exception {
        givenPersonExists(1L);

        mockMvc.perform(post("/api/v1/people/1/experiences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":999,\"company\":\"ACME\",\"role\":\"Backend Engineer\","
                                + "\"startDate\":\"2022-01-01\"}"))
                .andExpect(status().isBadRequest());
        verify(experienceRepository, never()).save(any());
    }
}
