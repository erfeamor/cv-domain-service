package com.erfeamor.cvdomain.skill;

import com.erfeamor.cvdomain.common.ClientSuppliedIds;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The global skill catalog, per docs/api-contract.md § Skills.
 *
 * <p><strong>This resource is not person-scoped and must never become so</strong> (design rule 2,
 * DoR 7). Its only failure modes are 400 (validation) and 409 (duplicate name); a person-existence
 * check on any endpoint here would be a contract violation, not a missing case. That is why no
 * {@code PersonRepository} is injected — the constructor makes the rule structural rather than a
 * comment someone has to remember.
 *
 * <p>There is deliberately no {@code DELETE /api/v1/skills/{id}} (DoR 9): the contract defines no
 * catalog delete, and the FK from {@code person_skill} cascades, so a catalog delete would silently
 * strip the skill from every person who had it. Adding one is a new task, not a gap.
 */
@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {

    private final SkillRepository skillRepository;

    public SkillController(SkillRepository skillRepository) {
        this.skillRepository = skillRepository;
    }

    @GetMapping
    public List<Skill> findAll() {
        return skillRepository.findAllByOrderByNameAscIdAsc();
    }

    /**
     * Creates a catalog skill; a duplicate {@code name} answers 409.
     *
     * <p><strong>The 409 comes from catching the constraint violation, never from a
     * read-before-write.</strong> A pre-check ({@code findByName} then reject) looks equivalent
     * and passes a single-threaded duplicate-POST test, but it loses the race: two concurrent
     * POSTs of the same new name both see "not found", both insert, and the loser surfaces as a
     * 500. The database's UNIQUE constraint is the only thing that actually serialises them, so
     * the catch is the implementation and not a safety net under one.
     *
     * <p>{@link ClientSuppliedIds#reject} is the first statement for the reason T-107 documents:
     * a body carrying an {@code id} binds despite {@code Skill.id} having no setter, and turns
     * this create into a {@code merge()} that renames an existing catalog row. On the catalog that
     * also rewrites what every person assigned to that skill appears to know, since the assignment
     * rows join to the name they never held a copy of.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Skill create(@Valid @RequestBody Skill skill) {
        ClientSuppliedIds.reject(skill.getId());
        try {
            return skillRepository.save(skill);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A skill with that name already exists", ex);
        }
    }
}
