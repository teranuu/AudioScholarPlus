# Security Architecture

This document outlines the security measures implemented in the AudioScholar frontend.

## Authentication

The application uses a dual-layer authentication strategy involving Firebase Authentication and a custom backend API.

### Firebase Integration

**File:** `src/config/firebaseConfig.js`

Firebase is used for the initial user sign-in and identity management (e.g., Google Sign-In, Email/Password). The configuration includes the API key, auth domain, and other project identifiers.

### Token Verification

**File:** `src/services/authService.js`

After a user signs in via Firebase on the frontend, the Firebase ID token is sent to the backend API for verification.

1.  **Frontend:** User authenticates with Firebase.
2.  **Frontend:** Gets `idToken` from Firebase.
3.  **Frontend:** Calls `authService.verifyFirebaseTokenWithBackend(idToken)` (or `verifyGoogleTokenWithBackend` for Google Sign-In).
4.  **Backend:** Verifies the token and issues a custom JWT (JSON Web Token) if valid.
5.  **Frontend:** Stores the backend JWT in `localStorage` as `AuthToken`.

## Authorization

### Protected Routes

**File:** `src/components/common/ProtectedRoute.jsx`

Client-side access control is enforced using the `ProtectedRoute` component. It checks for the presence of the `AuthToken` in `localStorage`. If missing, it redirects the user to the Sign In page.

### API Requests

All authenticated API requests include the JWT in the `Authorization` header:

```javascript
Authorization: `Bearer ${token}`
```

This is handled in the `getAuthHeaders` helper function within service files (e.g., `src/services/noteService.js`, `src/services/adminService.js`).

## Data Protection

- **HTTPS:** All API communication is performed over HTTPS (in production).
- **Sensitive Data:** No sensitive user data (passwords, private keys) is stored in the frontend codebase. Firebase project identifiers and OAuth client IDs are loaded from `VITE_*` environment variables documented in `.env.example`; do not place server-side secrets in frontend environment files.

## Cross-Origin Resource Sharing (CORS)

The backend API is expected to handle CORS to allow requests from the frontend domain.