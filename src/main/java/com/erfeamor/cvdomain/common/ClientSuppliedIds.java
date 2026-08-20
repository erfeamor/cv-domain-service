package com.erfeamor.cvdomain.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * One home for the rule that ids are assigned by the database and never by the client.
 *
 * <p><strong>This is an authorisation control, not tidiness.</strong> Every entity here declares
 * {@code id} as a private field with a getter and no setter, which reads as un-bindable. Jackson's
 * {@code MapperFeature.INFER_PROPERTY_MUTATORS} is on by default and binds the private field
 * anyway — verified empirically, a body carrying {@code "id": 999} deserializes to
 * {@code getId() == 999}.
 *
 * <p>From there the damage is done by Spring Data, not by the controller: a non-null id makes
 * {@code SimpleJpaRepository.save()} evaluate {@code isNew() == false} and call {@code em.merge()}
 * instead of {@code persist()}. For a person-scoped child the owning person has already been set
 * to the caller's, so the statement becomes
 * {@code UPDATE <table> SET person_id = <caller>, … WHERE id = 999} — <em>another person's row is
 * overwritten and handed to the caller, with a 201 and the victim's id in the response.</em> For
 * {@code Person} itself there is no cross-person dimension, and the damage is an unauthorised
 * overwrite of an arbitrary person record.
 *
 * <p>This is the same write that {@code findByIdAndPersonId} scopes PUT and DELETE against; it
 * simply arrives through the one verb that has no existing row to scope to. Filed as T-107 after
 * T-102's {@code /code-review} found it, having sat on the task board since refinement described
 * as a harmless "must not override the generated id".
 *
 * <p><strong>PUT needs no equivalent guard</strong> in any resource: every update path copies
 * fields onto the entity returned by its own lookup and saves <em>that</em>, so the request body's
 * id is never the id written.
 *
 * <p><strong>Why a 400 and not a silent ignore.</strong> {@code @JsonProperty(access = READ_ONLY)}
 * on each id would stop the binding structurally, and a new resource would inherit the protection
 * without its author knowing this rule exists — a real advantage, considered and declined. It
 * makes the server discard a supplied id in silence, so a client that sent one believing it was
 * updating a row receives a 201 for a different row and no indication it was wrong. Contract
 * design rule 4 puts client mistakes in the 400 family, and T-102 already shipped this behaviour
 * for education; two behaviours across sibling resources would be worse than either. The cost of
 * this choice is that it must be *called* — which is why the next two resources carry it in their
 * task files rather than relying on anyone reading this class.
 */
public final class ClientSuppliedIds {

    private ClientSuppliedIds() {
    }

    /**
     * Rejects a create whose payload carried an id.
     *
     * @param id the id bound from the request body, normally {@code null}
     * @throws ResponseStatusException 400 if an id was supplied
     */
    public static void reject(Long id) {
        if (id != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "id is assigned by the server and must not be supplied");
        }
    }
}
