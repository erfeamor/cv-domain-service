package com.erfeamor.cvdomain.skill;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer coverage for the global skill catalog (test-plan cases SC1-SC6).
 *
 * <p>Note what this class does <em>not</em> declare: a {@code PersonRepository} mock. The catalog
 * is global (DoR 7), so if anyone ever adds a person-existence check to {@code SkillController},
 * this context fails to start rather than quietly passing — the absence is the assertion.
 */
@WebMvcTest(SkillController.class)
@AutoConfigureMockMvc(addFilters = false)
class SkillControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SkillRepository skillRepository;

    private Skill skill(Long id, String name, String category) {
        Skill skill = new Skill(name, category);
        if (id != null) {
            // The id is database-generated, so a fixture that never touches one has a null id and
            // the contract-shape assertions cannot see the field.
            ReflectionTestUtils.setField(skill, "id", id);
        }
        return skill;
    }

    // SC1
    @Test
    void sc1ListsTheCatalogInTheContractShape() throws Exception {
        given(skillRepository.findAllByOrderByNameAscIdAsc()).willReturn(
                List.of(skill(1L, "Java", "Backend"), skill(2L, "Kubernetes", "Ops"),
                        skill(3L, "Vim", null)));

        mockMvc.perform(get("/api/v1/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                // Exactly three fields: a leaked association or an extra column shows up here.
                .andExpect(jsonPath("$[0].length()").value(3))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Java"))
                .andExpect(jsonPath("$[0].category").value("Backend"))
                .andExpect(jsonPath("$[2].category").value(org.hamcrest.Matchers.nullValue()));
    }

    // SC2
    @Test
    void sc2ReturnsAnEmptyArrayForAnEmptyCatalog() throws Exception {
        given(skillRepository.findAllByOrderByNameAscIdAsc()).willReturn(List.of());

        mockMvc.perform(get("/api/v1/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // SC3
    @Test
    void sc3CreatesASkill() throws Exception {
        given(skillRepository.save(any(Skill.class))).willAnswer(invocation -> {
            Skill toSave = invocation.getArgument(0);
            ReflectionTestUtils.setField(toSave, "id", 7L);
            return toSave;
        });

        mockMvc.perform(post("/api/v1/skills").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Java\",\"category\":\"Backend\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("Java"))
                .andExpect(jsonPath("$.category").value("Backend"));
    }

    // SC4 - DoR 3: name is required.
    @Test
    void sc4RejectsAMissingName() throws Exception {
        mockMvc.perform(post("/api/v1/skills").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"Backend\"}"))
                .andExpect(status().isBadRequest());
        verify(skillRepository, never()).save(any());
    }

    /** DoR 3 again: blank is not a name. {@code @NotNull} alone would let this through. */
    @Test
    void sc4RejectsABlankName() throws Exception {
        mockMvc.perform(post("/api/v1/skills").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \",\"category\":\"Backend\"}"))
                .andExpect(status().isBadRequest());
        verify(skillRepository, never()).save(any());
    }

    // SC5 - DoR 3: category is optional, and an omitted optional serializes as null.
    @Test
    void sc5CreatesASkillWithoutACategory() throws Exception {
        given(skillRepository.save(any(Skill.class))).willAnswer(invocation -> {
            Skill toSave = invocation.getArgument(0);
            ReflectionTestUtils.setField(toSave, "id", 8L);
            return toSave;
        });

        mockMvc.perform(post("/api/v1/skills").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Vim\"}"))
                .andExpect(status().isCreated())
                // PRESENT and null, not absent: doesNotExist() treats a JSON null as absent and
                // would pass for the wrong reason.
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.category").value(org.hamcrest.Matchers.nullValue()));
    }

    /**
     * SC6 — the discriminating test, and the highest-value one in this task.
     *
     * <p>A "POST the same name twice" test proves nothing about race safety: in a single-threaded
     * test a read-before-write pre-check also sees the duplicate and returns 409, so both
     * implementations pass it. This reproduces what the race actually looks like instead — any
     * pre-existence check reports NOT FOUND (there is no {@code findByName} on the repository to
     * stub, and every unstubbed Mockito call returns empty/null), while the constraint fires
     * inside {@code save()}.
     *
     * <p>Only a real {@code try/catch} around the write answers 409 here. A pre-check-only
     * implementation lets {@code DataIntegrityViolationException} propagate and 500s.
     */
    @Test
    void sc6DuplicateNameIs409FromTheCatchPathNotAPreCheck() throws Exception {
        given(skillRepository.save(any(Skill.class)))
                .willThrow(new DataIntegrityViolationException(
                        "Duplicate entry 'Java' for key 'skill.name'"));

        mockMvc.perform(post("/api/v1/skills").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Java\",\"category\":\"Backend\"}"))
                .andExpect(status().isConflict());
    }

    /**
     * T-107: a client-supplied id in a POST body must never reach the repository.
     *
     * <p>{@code Skill.id} is a private field with no setter, which reads as un-bindable; Jackson's
     * INFER_PROPERTY_MUTATORS binds it anyway. A non-null id makes Spring Data's {@code save()}
     * take {@code merge()} instead of {@code persist()}, so the write becomes {@code UPDATE skill
     * SET name = …, category = … WHERE id = 999} — it silently renames an existing catalog entry
     * and answers 201 with the victim's id. On the catalog that is worse than on a person-scoped
     * child: every person assigned skill 999 now shows the attacker's skill name, because the
     * assignment rows join to it and were never touched.
     *
     * <p>Asserting {@code never()).save()} is the load-bearing half. A test that only asserted the
     * status would still pass against a controller that ignored the id, and this one was written
     * against the version without {@code ClientSuppliedIds.reject} and observed failing (201, save
     * invoked) before the guard was added.
     */
    @Test
    void rejectsAClientSuppliedIdInsteadOfMergingOverAnExistingCatalogRow() throws Exception {
        mockMvc.perform(post("/api/v1/skills").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":999,\"name\":\"Java\",\"category\":\"Backend\"}"))
                .andExpect(status().isBadRequest());
        verify(skillRepository, never()).save(any());
    }

    /**
     * Ordering is the repository's job (contract § Ordering) — this asserts the controller passes
     * that order straight through instead of re-sorting, which is what keeps one answer for every
     * consumer.
     */
    @Test
    void passesTheRepositoryOrderThroughUnchanged() throws Exception {
        given(skillRepository.findAllByOrderByNameAscIdAsc())
                .willReturn(List.of(skill(9L, "Ansible", "Ops"), skill(2L, "Java", "Backend")));

        mockMvc.perform(get("/api/v1/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Ansible"))
                .andExpect(jsonPath("$[1].name").value("Java"));
        verify(skillRepository).findAllByOrderByNameAscIdAsc();
    }
}
