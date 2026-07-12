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
- **Security**: OAuth2 resource server validating Cognito JWTs. `AUTH_ENABLED=false` (default **true**) disables auth entirely for local stacks — never in deployed config. Health, prometheus, and swagger endpoints stay public either way. CORS origins come from `CORS_ALLOWED_ORIGINS`.
- `@MockBean` is fine on this Spring Boot version (3.3.x); don't bump Spring Boot in a feature PR.

## Git workflow

`master` is protected — feature branch (`feat/…`) → push → PR via `gh`, CI must pass. Definition of done for any code PR: tests for the new path + checkstyle clean.
