# CLAUDE.md — cv-domain-service

Core domain API for cv-project: Java 17, Spring Boot 3.3, JPA/Hibernate against MySQL (cv-database). **Source of truth** for all CV data — the BFF and admin UI are clients of this service. Part of the multi-repo workspace; cross-repo context lives in the meta repo's CLAUDE.md one directory up.

## Commands

```bash
mvn -B test                # full suite (JUnit 5 + Mockito + AssertJ)
mvn -B checkstyle:check    # lint (google_checks) — CI runs this first
mvn spring-boot:run        # start on :8080 (needs cv-database's MySQL up + migrated)
mvn -B package -DskipTests # build the jar
docker build -t cv-domain-service .   # multi-stage prod image
```

Swagger UI: `http://localhost:8080/swagger-ui.html`. Metrics: `/actuator/prometheus`. CI: `Jenkinsfile` (lint → test → package → image).

## Architecture & conventions

- **Package-by-feature**: each aggregate gets its own package under `com.erfeamor.cvdomain` (see `person/` — entity + `JpaRepository` + `@RestController` together). New resources copy this shape; there is no service layer until behavior demands one.
- REST style: person-scoped nesting `/api/v1/people/{personId}/<section>`; 201 on create, 204 on delete, 404 via `EntityNotFoundException` + local `@ExceptionHandler` (see `PersonController`). The API contract in the meta repo (`docs/api-contract.md`) is binding.
- Bean validation on entities (`@NotBlank`, `@Email`); invalid payloads → 400 automatically.
- Tests: `@WebMvcTest(addFilters = false)` with mocked repository for controllers; `@DataJpaTest` for persistence. Both patterns exist in `src/test/java/.../person/` — copy them.

## Critical gotchas

- **Schema is owned by cv-database (Flyway), not by JPA.** `ddl-auto: validate` in prod config — entity mappings must match the migration's column names exactly, and *new columns mean a migration PR in cv-database first*.
- Tests override to H2 + `create-drop` via `src/test/resources/application.yml`. Don't "fix" main config to make a test pass.
- **Security**: OAuth2 resource server validating Cognito JWTs. `AUTH_ENABLED=false` (default **true**) disables auth entirely for local stacks — never in deployed config. **Only `/actuator/health` is anonymous when auth is on** (T-106). `/v3/api-docs`, `/swagger-ui/**` and `/actuator/prometheus` used to be public too, which is why the deployed box served its whole OpenAPI document to anyone who asked; they now require a token. This costs local dev nothing — local stacks run `AUTH_ENABLED=false` and take the permit-all branch, so Swagger UI and Prometheus scraping work exactly as before on :8080. Deploying a scraper against this service means giving it a token. CORS origins come from `CORS_ALLOWED_ORIGINS`.
- `@MockBean` is fine on this Spring Boot version (3.3.x); don't bump Spring Boot in a feature PR.

## Code review guidance

Priorities, ranked:

1. **Contract conformance.** Status codes and payload shapes must match `docs/api-contract.md` exactly (person-scoped nesting, 201/204/404/400, and — for skills — the 409 on duplicate catalog name and the upsert semantics on assignment PUT).
2. **Schema/entity mismatch.** New columns or types must trace back to a cv-database migration; flag any entity field with no corresponding column.
3. **Missing test pairing.** A new resource without both a `@WebMvcTest` (mocked repo) and a `@DataJpaTest` (persistence) is incomplete — both patterns must exist per aggregate, not just one.
4. N+1 queries or missing `@Transactional` boundaries on multi-step writes (e.g. the skill upsert path).

Don't flag:
- No service layer for a simple aggregate — package-by-feature with entity + repository + controller is the deliberate default until behavior demands one.
- `@MockBean` usage on this Spring Boot version (3.3.x) — it's fine here, don't suggest replacing it.
- `AUTH_ENABLED=false` in test/local config — only flag it if it appears in a deployed/prod config path.

## Git workflow

`master` is protected — feature branch (`feat/…`) → push → PR via `gh`, CI must pass. Definition of done for any code PR: tests for the new path + checkstyle clean.
