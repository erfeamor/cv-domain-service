# cv-domain-service

Core domain API for the Currículum Interactivo project: the source of truth for résumé data, backed by [cv-database](../cv-database).

Part of the [cv-project](../README.md) multi-repo. Pipeline: Jenkins.

## Stack

- Java 17, Spring Boot 3
- Spring Data JPA / Hibernate
- Spring Security (OAuth2 resource server, validates AWS Cognito JWTs)
- springdoc-openapi (Swagger UI at `/swagger-ui.html`)
- Micrometer → Prometheus (`/actuator/prometheus`)
- JUnit 5 + Mockito + AssertJ (TDD)

## Local development

Requires [cv-database](../cv-database) running (`docker compose up -d && ./scripts/migrate.sh` there first).

```bash
mvn spring-boot:run     # start on :8080
mvn test                 # run the test suite
```

Set `COGNITO_ISSUER_URI` to your Cognito user pool's issuer URL; unauthenticated requests are only permitted on `/actuator/health`, `/actuator/prometheus`, and the Swagger endpoints.

## API

- `GET /api/v1/people`
- `GET /api/v1/people/{id}`
- `POST /api/v1/people`
- `PUT /api/v1/people/{id}`
- `DELETE /api/v1/people/{id}`

More resources (`experience`, `education`, `skill`, `project`) follow the same pattern — see the roadmap in [cv-project](../README.md).
