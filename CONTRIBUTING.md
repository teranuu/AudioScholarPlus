# Contributing to AudioScholar

Thanks for helping revive AudioScholar. Keep changes scoped to the component you are touching and preserve existing behavior unless a change is explicitly approved.

## Local validation

Run the checks for the component you changed:

```bash
# Backend
cd backend/audioscholar
./mvnw -B test
./mvnw -B spotless:check

# Web
cd frontend_web/audioscholar-app
npm ci
npm run lint
npm run test
npm run build
npm audit --audit-level=high

# Mobile
cd frontend_mobile/AudioScholar
./gradlew test
./gradlew lint
./gradlew assembleDebug
```

`./mvnw -B dependency-check:check` requires `NVD_API_KEY` for reliable OWASP Dependency-Check runs.

## Secrets

Do not commit `.env`, Firebase service accounts, Android `google-services.json`, OAuth secrets, API keys, JWT secrets, or generated build artifacts.
