package com.erfeamor.cvdomain.personskill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erfeamor.cvdomain.person.Person;
import com.erfeamor.cvdomain.person.PersonRepository;
import com.erfeamor.cvdomain.skill.Skill;
import com.erfeamor.cvdomain.skill.SkillRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer coverage for person-scoped skill assignments (test-plan cases SA1-SA14).
 */
@WebMvcTest(PersonSkillController.class)
@AutoConfigureMockMvc(addFilters = false)
class PersonSkillControllerTest {

    private static final String ADVANCED_BODY = "{\"proficiency\":\"ADVANCED\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PersonSkillRepository personSkillRepository;

    @MockBean
    private PersonRepository personRepository;

    @MockBean
    private SkillRepository skillRepository;

    private Person person(Long id) {
        Person person = new Person("Jane Doe", "Engineer", "jane@example.com", "Remote", "Bio");
        ReflectionTestUtils.setField(person, "id", id);
        return person;
    }

    private Skill skill(Long id, String name, String category) {
        Skill skill = new Skill(name, category);
        ReflectionTestUtils.setField(skill, "id", id);
        return skill;
    }

    private PersonSkill assignment(Long skillId, String name, String category,
            Proficiency proficiency) {
        return new PersonSkill(person(1L), skill(skillId, name, category), proficiency);
    }

    private void personExists() {
        given(personRepository.findById(1L)).willReturn(Optional.of(person(1L)));
    }

    private void personAbsent() {
        given(personRepository.findById(999L)).willReturn(Optional.empty());
    }

    private void skillExists() {
        given(skillRepository.findById(5L)).willReturn(Optional.of(skill(5L, "Java", "Backend")));
    }

    private void skillAbsent() {
        given(skillRepository.findById(999L)).willReturn(Optional.empty());
    }

    // SA1
    @Test
    void sa1ListsAssignmentsInTheJoinedContractShape() throws Exception {
        personExists();
        given(personSkillRepository.findByPersonIdOrdered(1L)).willReturn(
                List.of(assignment(5L, "Java", "Backend", Proficiency.ADVANCED),
                        assignment(6L, "Vim", null, Proficiency.BEGINNER)));

        mockMvc.perform(get("/api/v1/people/1/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].skillId").value(5))
                .andExpect(jsonPath("$[0].name").value("Java"))
                .andExpect(jsonPath("$[0].category").value("Backend"))
                .andExpect(jsonPath("$[0].proficiency").value("ADVANCED"))
                .andExpect(jsonPath("$[1].category").value(org.hamcrest.Matchers.nullValue()));
    }

    // SA2 - an existing person with no assignments is an empty collection, not a 404.
    @Test
    void sa2ReturnsAnEmptyArrayWhenThePersonHasNoAssignments() throws Exception {
        personExists();
        given(personSkillRepository.findByPersonIdOrdered(1L)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/people/1/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // SA3
    @Test
    void sa3UnknownPersonOnGetIs404() throws Exception {
        personAbsent();
        mockMvc.perform(get("/api/v1/people/999/skills")).andExpect(status().isNotFound());
    }

    /**
     * SA4 — DoR 5 and DoR 2: person and skill exist, nothing links them, and PUT <em>creates</em>
     * the row and answers <strong>200</strong>. Not 201 (the contract has no 201 on this verb) and
     * not 404 (an absent link is not an unknown id).
     *
     * <p>Deliberately a separate test from SA13, which sends DELETE into the identical
     * precondition and expects 404. The two verbs diverge on "absent" on purpose, so neither
     * result may be inferred from the other.
     */
    @Test
    void sa4PutOnAnUnlinkedPairCreatesTheRowAndReturns200() throws Exception {
        personExists();
        skillExists();
        given(personSkillRepository.findById(new PersonSkillId(1L, 5L)))
                .willReturn(Optional.empty());
        given(personSkillRepository.save(any(PersonSkill.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(put("/api/v1/people/1/skills/5")
                        .contentType(MediaType.APPLICATION_JSON).content(ADVANCED_BODY))
                .andExpect(status().isOk())
                // The response is the joined GET shape, not an echo of the request body.
                .andExpect(jsonPath("$.skillId").value(5))
                .andExpect(jsonPath("$.name").value("Java"))
                .andExpect(jsonPath("$.category").value("Backend"))
                .andExpect(jsonPath("$.proficiency").value("ADVANCED"));

        ArgumentCaptor<PersonSkill> saved = ArgumentCaptor.forClass(PersonSkill.class);
        verify(personSkillRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(new PersonSkillId(1L, 5L));
    }

    /**
     * SA5 — the update branch: also 200, and the write must land on the <em>existing</em>
     * composite id rather than becoming a second insert.
     */
    @Test
    void sa5PutOnAnExistingAssignmentUpdatesItInPlaceAndReturns200() throws Exception {
        personExists();
        skillExists();
        PersonSkill existing = assignment(5L, "Java", "Backend", Proficiency.BEGINNER);
        given(personSkillRepository.findById(new PersonSkillId(1L, 5L)))
                .willReturn(Optional.of(existing));
        given(personSkillRepository.save(any(PersonSkill.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(put("/api/v1/people/1/skills/5")
                        .contentType(MediaType.APPLICATION_JSON).content(ADVANCED_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skillId").value(5))
                .andExpect(jsonPath("$.proficiency").value("ADVANCED"));

        ArgumentCaptor<PersonSkill> saved = ArgumentCaptor.forClass(PersonSkill.class);
        verify(personSkillRepository).save(saved.capture());
        // The row that was read is the row that is written: same instance, same composite id.
        assertThat(saved.getValue()).isSameAs(existing);
        assertThat(saved.getValue().getId()).isEqualTo(new PersonSkillId(1L, 5L));
        assertThat(saved.getValue().getProficiency()).isEqualTo(Proficiency.ADVANCED);
    }

    // SA6 - an unknown enum constant is a client error, not a 500.
    @Test
    void sa6RejectsAnInvalidProficiencyValue() throws Exception {
        personExists();
        skillExists();

        mockMvc.perform(put("/api/v1/people/1/skills/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proficiency\":\"NOT_A_LEVEL\"}"))
                .andExpect(status().isBadRequest());
        verify(personSkillRepository, never()).save(any());
    }

    /**
     * SA7 — a body with no {@code proficiency} is a 400. V1's column carries {@code DEFAULT
     * 'INTERMEDIATE'}, which makes "just let it default" look reasonable; Hibernate writes an
     * explicit NULL and never reaches that default, and silently inventing a level would hide the
     * client's mistake either way.
     */
    @Test
    void sa7RejectsAMissingProficiency() throws Exception {
        personExists();
        skillExists();

        mockMvc.perform(put("/api/v1/people/1/skills/5")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verify(personSkillRepository, never()).save(any());
    }

    // SA8 - DoR 8: the person check runs first and wins the 404.
    @Test
    void sa8UnknownPersonOnPutIs404() throws Exception {
        personAbsent();

        mockMvc.perform(put("/api/v1/people/999/skills/5")
                        .contentType(MediaType.APPLICATION_JSON).content(ADVANCED_BODY))
                .andExpect(status().isNotFound());
        verify(personSkillRepository, never()).save(any());
    }

    /**
     * SA9 — DoR 4: an unknown {@code skillId} is a 404. PUT upserts the <em>assignment</em>, never
     * the catalog entry, so this must not quietly create skill 999.
     */
    @Test
    void sa9UnknownSkillOnPutIs404AndNeverCreatesACatalogEntry() throws Exception {
        personExists();
        skillAbsent();

        mockMvc.perform(put("/api/v1/people/1/skills/999")
                        .contentType(MediaType.APPLICATION_JSON).content(ADVANCED_BODY))
                .andExpect(status().isNotFound());
        verify(personSkillRepository, never()).save(any());
        verify(skillRepository, never()).save(any());
    }

    // SA10
    @Test
    void sa10DeletesAnExistingAssignment() throws Exception {
        personExists();
        skillExists();
        PersonSkill existing = assignment(5L, "Java", "Backend", Proficiency.ADVANCED);
        given(personSkillRepository.findById(new PersonSkillId(1L, 5L)))
                .willReturn(Optional.of(existing));

        mockMvc.perform(delete("/api/v1/people/1/skills/5"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(personSkillRepository).delete(existing);
    }

    // SA11
    @Test
    void sa11UnknownPersonOnDeleteIs404() throws Exception {
        personAbsent();

        mockMvc.perform(delete("/api/v1/people/999/skills/5"))
                .andExpect(status().isNotFound());
        verify(personSkillRepository, never()).delete(any());
    }

    // SA12
    @Test
    void sa12UnknownSkillOnDeleteIs404() throws Exception {
        personExists();
        skillAbsent();

        mockMvc.perform(delete("/api/v1/people/1/skills/999"))
                .andExpect(status().isNotFound());
        verify(personSkillRepository, never()).delete(any());
    }

    /**
     * SA13 — DoR 6, and the mirror image of SA4: the same precondition (person and skill both
     * exist, no linking row) answers <strong>404</strong> on DELETE, not an idempotent 204. There
     * is no existing resource to remove, and design rule 4's 204 covers removing one that exists.
     */
    @Test
    void sa13DeletingAnUnlinkedPairIs404NotAnIdempotent204() throws Exception {
        personExists();
        skillExists();
        given(personSkillRepository.findById(new PersonSkillId(1L, 5L)))
                .willReturn(Optional.empty());

        mockMvc.perform(delete("/api/v1/people/1/skills/5"))
                .andExpect(status().isNotFound());
        verify(personSkillRepository, never()).delete(any());
    }

    /**
     * SA14 — the whole response object, field for field, on both verbs that return one. The
     * per-field assertions above would not catch a leaked {@code personId}, a surrogate {@code id}
     * or a nested {@code skill} object; {@code length()} does.
     */
    @Test
    void sa14GetResponseMatchesTheContractShapeExactly() throws Exception {
        personExists();
        given(personSkillRepository.findByPersonIdOrdered(1L))
                .willReturn(List.of(assignment(5L, "Java", "Backend", Proficiency.EXPERT)));

        mockMvc.perform(get("/api/v1/people/1/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].length()").value(4))
                .andExpect(jsonPath("$[0].skillId").value(5))
                .andExpect(jsonPath("$[0].name").value("Java"))
                .andExpect(jsonPath("$[0].category").value("Backend"))
                .andExpect(jsonPath("$[0].proficiency").value("EXPERT"))
                .andExpect(jsonPath("$[0].id").doesNotExist())
                .andExpect(jsonPath("$[0].personId").doesNotExist())
                .andExpect(jsonPath("$[0].person").doesNotExist())
                .andExpect(jsonPath("$[0].skill").doesNotExist());
    }

    @Test
    void sa14PutResponseMatchesTheContractShapeExactly() throws Exception {
        personExists();
        skillExists();
        given(personSkillRepository.findById(new PersonSkillId(1L, 5L)))
                .willReturn(Optional.empty());
        given(personSkillRepository.save(any(PersonSkill.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(put("/api/v1/people/1/skills/5")
                        .contentType(MediaType.APPLICATION_JSON).content(ADVANCED_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.skillId").value(5))
                .andExpect(jsonPath("$.name").value("Java"))
                .andExpect(jsonPath("$.category").value("Backend"))
                .andExpect(jsonPath("$.proficiency").value("ADVANCED"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.personId").doesNotExist())
                .andExpect(jsonPath("$.person").doesNotExist())
                .andExpect(jsonPath("$.skill").doesNotExist());
    }

    /**
     * The id written comes from the path, never from the body. A body that repeats or forges
     * {@code personId}/{@code skillId} must not redirect the write to another person's row — the
     * PUT analogue of the T-107 hole, which the guard on POST does not cover because this verb
     * has no {@code create()} to guard.
     */
    @Test
    void ignoresIdsInThePutBodyAndWritesThePathPair() throws Exception {
        personExists();
        skillExists();
        given(personSkillRepository.findById(new PersonSkillId(1L, 5L)))
                .willReturn(Optional.empty());
        given(personSkillRepository.save(any(PersonSkill.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(put("/api/v1/people/1/skills/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proficiency\":\"ADVANCED\",\"personId\":2,\"skillId\":77,"
                                + "\"name\":\"Forged\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skillId").value(5))
                .andExpect(jsonPath("$.name").value("Java"));

        ArgumentCaptor<PersonSkill> saved = ArgumentCaptor.forClass(PersonSkill.class);
        verify(personSkillRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(new PersonSkillId(1L, 5L));
    }

    /** The controller passes the repository's contract order through instead of re-sorting. */
    @Test
    void passesTheRepositoryOrderThroughUnchanged() throws Exception {
        personExists();
        given(personSkillRepository.findByPersonIdOrdered(1L)).willReturn(
                List.of(assignment(9L, "Ansible", "Ops", Proficiency.BEGINNER),
                        assignment(5L, "Java", "Backend", Proficiency.EXPERT),
                        assignment(6L, "Vim", null, Proficiency.ADVANCED)));

        mockMvc.perform(get("/api/v1/people/1/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Ansible"))
                .andExpect(jsonPath("$[1].name").value("Java"))
                .andExpect(jsonPath("$[2].name").value("Vim"));
        verify(personSkillRepository).findByPersonIdOrdered(1L);
    }
}
