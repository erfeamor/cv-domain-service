package com.erfeamor.cvdomain.personskill;

import com.erfeamor.cvdomain.person.Person;
import com.erfeamor.cvdomain.person.PersonRepository;
import com.erfeamor.cvdomain.skill.Skill;
import com.erfeamor.cvdomain.skill.SkillRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Person-scoped skill assignments, per docs/api-contract.md § Skills.
 *
 * <p>A different resource from the catalog, with its own 404 semantics. This endpoint never
 * creates a catalog entry: PUT of a {@code skillId} that is not in the catalog is a 404 (design
 * rule 4, DoR 4), not an implicit "create the skill too".
 *
 * <p><strong>Check order is fixed</strong> (DoR 8): person, then skill, then — DELETE only — the
 * linking row. When more than one is absent the person check wins, and every one of them answers
 * the same body, so a client can never use the 404 text to probe which id exists.
 *
 * <p><strong>PUT and DELETE diverge on an absent link, deliberately.</strong> A person and skill
 * that both exist with no row linking them is not an unknown id: PUT creates the link and returns
 * 200 (DoR 5), DELETE returns 404 because there is no existing resource to remove (DoR 6).
 * Implementing these two symmetrically — an idempotent 204 delete — is the easy accident here.
 */
@RestController
@RequestMapping("/api/v1/people/{personId}/skills")
public class PersonSkillController {

    /**
     * One message for every 404 this resource can produce — unknown person, unknown skill and an
     * unlinked pair all look identical to the client, so the body never discloses which id missed.
     */
    private static final String NOT_FOUND_MESSAGE = "Skill assignment not found";

    private final PersonSkillRepository personSkillRepository;
    private final PersonRepository personRepository;
    private final SkillRepository skillRepository;
    private final TransactionTemplate transactionTemplate;

    public PersonSkillController(PersonSkillRepository personSkillRepository,
            PersonRepository personRepository, SkillRepository skillRepository,
            TransactionTemplate transactionTemplate) {
        this.personSkillRepository = personSkillRepository;
        this.personRepository = personRepository;
        this.skillRepository = skillRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @GetMapping
    public List<PersonSkill> findAll(@PathVariable Long personId) {
        requirePerson(personId);
        // An existing person with no assignments is an empty collection, not a 404.
        return personSkillRepository.findByPersonIdOrdered(personId);
    }

    /**
     * Upserts an assignment: 200 on both branches, never 201 (contract § Skills, DoR 2).
     *
     * <p><strong>What the transaction buys, and what it does not.</strong> The body of an attempt
     * is a check-then-act — read the pair, then conditionally insert or update. The transaction
     * makes that one unit of work and, just as importantly, keeps the {@link Skill} loaded by
     * {@link #requireSkill} managed in the same persistence context as the write, so {@code
     * merge()} resolves the association to that initialized instance instead of a bare proxy;
     * with {@code open-in-view: false} an uninitialized proxy would blow up while serializing
     * {@code name} and {@code category} after the transaction had closed.
     *
     * <p>It does <strong>not</strong> serialize concurrent inserts. Two PUTs on the same unlinked
     * pair both read empty and both insert; the loser fails ER_DUP_ENTRY on the composite primary
     * key, and the contract requires it to answer 200 rather than 500 — the same hazard, and the
     * same treatment, as the catalog's duplicate-name 409.
     *
     * <p><strong>Why a {@code TransactionTemplate} rather than {@code @Transactional} here.</strong>
     * Recovery cannot happen inside the failed transaction: after a flush failure Hibernate
     * refuses further work in that session ({@code AssertionFailure: don't flush the Session after
     * an exception occurs}) and the transaction is rollback-only, so a catch-and-re-read in place
     * would merely trade one 500 for another — verified, not assumed. The retry therefore needs a
     * fresh transaction and a fresh persistence context, which is what a second {@code execute}
     * gives. It also puts the commit inside this method: with an annotation the INSERT is flushed
     * at commit <em>after</em> the handler returns, so the violation could not be caught here at
     * all.
     *
     * <p>One retry, not a loop: the second attempt takes the update branch against the row the
     * winner committed. It could only fail again if that row were deleted in the interval, which
     * is a genuine conflict rather than a race worth papering over.
     *
     * <p>The request body carries {@code proficiency} alone; {@code personId} and {@code skillId}
     * come from the path. The id written is built from the path variables, so a body that repeats
     * (or forges) them cannot redirect the write to another person's row.
     */
    @PutMapping("/{skillId}")
    public PersonSkill upsert(@PathVariable Long personId, @PathVariable Long skillId,
            @Valid @RequestBody PersonSkill body) {
        try {
            return transactionTemplate.execute(
                    status -> applyUpsert(personId, skillId, body.getProficiency()));
        } catch (DataIntegrityViolationException lostTheInsertRace) {
            return transactionTemplate.execute(
                    status -> applyUpsert(personId, skillId, body.getProficiency()));
        }
    }

    /** One attempt at the upsert, run inside a single transaction by the caller. */
    private PersonSkill applyUpsert(Long personId, Long skillId, Proficiency proficiency) {
        Person person = requirePerson(personId);
        Skill skill = requireSkill(skillId);
        PersonSkill assignment = personSkillRepository
                .findById(new PersonSkillId(personId, skillId))
                .orElseGet(() -> new PersonSkill(person, skill, proficiency));
        assignment.setProficiency(proficiency);
        return personSkillRepository.save(assignment);
    }

    /**
     * Removes an assignment: 204 when the link exists, 404 when it does not (DoR 6) — the one
     * place this resource is deliberately not symmetric with PUT.
     */
    @DeleteMapping("/{skillId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable Long personId, @PathVariable Long skillId) {
        requirePerson(personId);
        requireSkill(skillId);
        PersonSkill existing = personSkillRepository
                .findById(new PersonSkillId(personId, skillId))
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_MESSAGE));
        personSkillRepository.delete(existing);
    }

    /** Resolved first on every verb, so an unknown person always wins the 404 (DoR 8). */
    private Person requirePerson(Long personId) {
        return personRepository.findById(personId)
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_MESSAGE));
    }

    /** The catalog is only read here — an unknown skill is a 404, never an implicit create. */
    private Skill requireSkill(Long skillId) {
        return skillRepository.findById(skillId)
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_MESSAGE));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<String> handleNotFound(EntityNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }
}
