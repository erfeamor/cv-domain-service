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

Swagger UI: `http://localhost:8080/swagger-ui.html`. Metrics: `/actuator/prometheus`. CI: `Jenkinsfile` (lint → test → package → image), bounded by a 20-minute pipeline timeout; the `Deploy` stage (gated on `master`) only points at the deploy workflow. See *CI and deploy* below.

## CI and deploy (T-112)

- **Jenkins is the CI and test gate** (`Jenkinsfile`: checkstyle, tests, package, image build). It holds **no deploy credential**: every build on the CI host is effectively root through `docker.sock` (T-005).
- **GitHub Actions deploys** (`.github/workflows/deploy.yml`, master pushes only). Job `wait-for-jenkins` polls the commit statuses API for the newest `continuous-integration/jenkins/branch` status on that commit (up to 30 min, since the CI host is woken on demand): `success` deploys, `failure`/`error` or nothing green in time blocks. Job `deploy` assumes the OIDC role in the repo variable **`AWS_DEPLOY_ROLE_ARN`**, builds a multi-arch (`linux/amd64,linux/arm64`) image, pushes `:latest` and `:<sha>` to ECR `cv-project-domain-service`, sends SSM document `cv-redeploy-domain-service` to the host tagged `Name=cv-project-domain-service`, waits for the invocation, then smoke-checks the BFF aggregate (200) and the domain API without a token (401).
- **Rollback:** retag a previous image as `:latest` (`aws ecr batch-get-image` on `:<sha>` → `put-image --image-tag latest` with the same manifest) and re-send the document (`aws ssm send-command --document-name cv-redeploy-domain-service --targets Key=tag:Name,Values=cv-project-domain-service`), or follow the cv-infra runbook. The ECR lifecycle policy keeps only a few images, so only recent SHAs are available.

## Architecture & conventions

- **Package-by-feature**: each aggregate gets its own package under `com.erfeamor.cvdomain` (see `person/` — entity + `JpaRepository` + `@RestController` together). New resources copy this shape; there is no service layer until behavior demands one.
- REST style: person-scoped nesting `/api/v1/people/{personId}/<section>`; 201 on create, 204 on delete, 404 via `EntityNotFoundException` + local `@ExceptionHandler` (see `PersonController`). The API contract in the meta repo (`docs/api-contract.md`) is binding.
- Bean validation on entities (`@NotBlank`, `@Email`); invalid payloads → 400 automatically.
- Tests: `@WebMvcTest(addFilters = false)` with mocked repository for controllers; `@DataJpaTest` for persistence. Both patterns exist in `src/test/java/.../person/` — copy them.

## Critical gotchas

- **Schema is owned by cv-database (Flyway), not by JPA.** `ddl-auto: validate` in prod config — entity mappings must match the migration's column names exactly, and *new columns mean a migration PR in cv-database first*. Since T-112 a master push here **deploys itself**, so the order is strict (T-158): the cv-database migration PR merges **first**, and its `migrate` workflow run (which applies it to production through SSM `cv-redeploy-migrate`) must be **green before** the entity change merges here. Merged the other way round, the new image fails Hibernate's `validate` at startup and the API goes down. A migration must stay additive so the running image keeps working on the new schema.
- Tests override to H2 + `create-drop` via `src/test/resources/application.yml`. Don't "fix" main config to make a test pass.
- **Security**: OAuth2 resource server validating Cognito JWTs. `AUTH_ENABLED=false` (default **true**) disables auth entirely for local stacks — never in deployed config. **Only `/actuator/health` is anonymous when auth is on** (T-106). `/v3/api-docs`, `/swagger-ui/**` and `/actuator/prometheus` used to be public too, which is why the deployed box served its whole OpenAPI document to anyone who asked; they now require a token. This costs local dev nothing — local stacks run `AUTH_ENABLED=false` and take the permit-all branch, so Swagger UI and Prometheus scraping work exactly as before on :8080. Deploying a scraper against this service means giving it a token. **Writes require a user token (T-116):** POST/PUT/PATCH/DELETE need `scope` to contain `openid` (`SCOPE_openid`), which only Cognito hosted-UI user tokens carry; client-credentials (machine) tokens such as the BFF's `cv-domain/read` are read-only and get 403 on a write. GET/HEAD/OPTIONS accept any pool token. CSRF is off in the auth branch (bearer-only, stateless), so a token-less write is 401. CORS origins come from `CORS_ALLOWED_ORIGINS`.
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
