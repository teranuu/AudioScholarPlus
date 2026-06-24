# Security Policy

## Reporting a vulnerability

Please report suspected vulnerabilities privately to the repository maintainers rather than opening a public issue. Include affected component, impact, reproduction steps, and any relevant logs with secrets removed.

## Supported branch

Security fixes target the `main` branch unless a release branch is created.

## Baseline checks

- Web: `npm audit --audit-level=high`
- Backend: `./mvnw -B dependency-check:check` with `NVD_API_KEY`
- Secrets: never commit `.env`, Firebase service accounts, `google-services.json`, OAuth secrets, API keys, or JWT secrets.
