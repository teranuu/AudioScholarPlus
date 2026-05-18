# 🎓 AudioScholar: Transforming Audio into Actionable Insights for Learners

[![CI](https://github.com/MasuRii/AudioScholar/actions/workflows/ci.yml/badge.svg)](https://github.com/MasuRii/AudioScholar/actions/workflows/ci.yml)
![License](https://img.shields.io/badge/license-MIT-blue)
![Backend Version](https://img.shields.io/badge/backend-0.0.1--SNAPSHOT-orange)

AudioScholar is an intelligent, multi-user platform designed to record lecture audio and leverage AI-driven summarization techniques to produce structured insights for learners. As a dual-platform solution comprising an Android mobile application and a comprehensive web interface, it allows users to capture, summarize, and receive personalized learning material recommendations based on audio recordings. By transforming lengthy lectures into digestible key points, AudioScholar enhances note-taking efficiency and content comprehension.

For the most detailed product overview, see [`docs/README-AudioScholar.md`](docs/README-AudioScholar.md). This root README is the contributor setup and validation entry point.

<p align="center">
<img src="logo/AudioScholarLogoNoBG.png" alt="AudioScholar Logo" width="250"/>
   <br>
  <img src="https://cit.edu/wp-content/uploads/2023/07/cit-logo.png" alt="CITU Logo" width="350"/>
</p>

---

## 🚀 Key Features

### 🎙️ Lecture Recording & Processing
- **Smart Recording:** Record lectures in real-time (online or offline) using the Android mobile app.
- **Audio Uploads:** Upload pre-recorded audio files from both mobile and web interfaces.
- **Playback & Management:** Centralized library with advanced playback controls and organizational tools.

### 🧠 AI-Driven Insights
- **Intelligent Summarization:** Leverages **Google Gemini AI API** to generate structured summaries, key topics, and glossaries.
- **Contextual Enhancement:** Upload PowerPoint presentations to augment AI processing for higher accuracy.
- **Smart Recommendations:** Automatically suggests relevant **YouTube** learning materials based on lecture content.

### 🌐 Platform & Connectivity
- **Cross-Platform Access:** Seamless experience across **Android Mobile App** and **Web Interface**.
- **Cloud Synchronization:** Securely sync recordings and summaries to **Nhost Storage** for access on any device.
- **Offline Capabilities:** Record and access local data on mobile even without an internet connection.

### 👤 User Experience & Security
- **Flexible Authentication:** Secure login via **Google**, **GitHub**, or Email/Password using **Firebase Authentication**.
- **User Notes:** Integrated note-taking system to add personal insights alongside AI summaries.
- **Freemium Architecture:** Tiered feature access distinguishing between Free and Premium user experiences.
- **Admin Dashboard:** Powerful tools for user management, analytics, and system monitoring.

---

## ⚙️ Setup Instructions

### 📌 Prerequisites
Ensure the following tools are installed on your system:

- **Java Development Kit (JDK) 24**
- **Node.js** (v18+)
- **npm** or **yarn**
- **Git**
- **Maven** (for backend)
- **Android Studio** (Latest version)
- **Nhost Account** (for cloud file storage - [Nhost Cloud](https://nhost.io/))
- **Firebase Account** (for authentication and Firestore database - [Firebase Console](https://console.firebase.google.com/))

---

### 📁 Cloning the Repository
```bash
git clone https://github.com/MasuRii/AudioScholar.git
cd AudioScholar
```

---

### 🔧 Backend Setup (Spring Boot)
1. Navigate to the backend:
   ```bash
   cd backend/audioscholar
   ```
2. **Required Configuration Files:**
   The backend requires two specific files to function correctly. You must create/place them in the specified locations:

   *   **`.env` file**: Required at `backend\audioscholar\.env`
   *   **Firebase Service Account**: Required at `backend\audioscholar\src\main\resources\firebase-service-account.json`

3. **Set up `.env` content:**
   Create the `.env` file in `backend/audioscholar/` with the following variables:
   ```dotenv
   # Copy from backend/audioscholar/.env.example and fill deployment-specific values.
   SPRING_PROFILES_ACTIVE=local
   APP_CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:8080,capacitor://localhost

   FIREBASE_WEB_API_KEY=your-firebase-web-api-key
   FIREBASE_DATABASE_URL=https://your-project.firebaseio.com
   GOOGLE_CLIENT_ID=your-google-oauth-client-id
   GOOGLE_CLIENT_SECRET=your-google-oauth-client-secret
   GOOGLE_ANDROID_CLIENT_ID=your-google-android-client-id
   GOOGLE_AI_API_KEY=your-gemini-api-key
   GEMINI_API_KEYS=your-gemini-api-key-or-comma-separated-keys
   YOUTUBE_API_KEY=your-youtube-api-key

   NHOST_STORAGE_URL=https://your-nhost-project.storage.region.nhost.run/v1/files
   NHOST_ADMIN_SECRET=your-nhost-admin-secret
   GITHUB_CLIENT_ID=your-github-oauth-client-id
   GITHUB_CLIENT_SECRET=your-github-oauth-client-secret
   JWT_SECRET=your-strong-jwt-secret
   CONVERTAPI_SECRET=your-convertapi-secret
   CONVERTAPI_SECRETS=your-convertapi-secret-or-comma-separated-secrets
   UPTIME_ROBOT_API=your-uptime-robot-api-key
   NVD_API_KEY=your-nvd-api-key
   ```

4. **Configure Application Properties:**
   Keep secrets and deployment identifiers in `.env` or environment variables. `application.properties` reads Firebase, Nhost, CORS, OAuth, JWT, Gemini, YouTube, ConvertAPI, and UptimeRobot values from those variables.

5. Run the backend:
   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
   ```
   Or run `AudioscholarApplication.java` from your IDE. (Spring Boot version `3.5.8`)

---

### 💻 Web Frontend Setup (React + Vite)
1. Navigate to the web app:
   ```bash
   cd frontend_web/audioscholar-app
   ```
2. Install dependencies:
   ```bash
   npm ci
   ```
3. Create a `.env` file in `frontend_web/audioscholar-app`:
   ```dotenv
   # Backend API URL
   VITE_API_URL=http://localhost:8080

   # Firebase Frontend Configuration
   VITE_FIREBASE_API_KEY=your-firebase-api-key
   VITE_FIREBASE_AUTH_DOMAIN=your-firebase-auth-domain
   VITE_FIREBASE_DATABASE_URL=your-firebase-database-url
   VITE_FIREBASE_PROJECT_ID=your-firebase-project-id
   VITE_FIREBASE_STORAGE_BUCKET=your-firebase-storage-bucket
   VITE_FIREBASE_MESSAGING_SENDER_ID=your-firebase-messaging-sender-id
   VITE_FIREBASE_APP_ID=your-firebase-app-id
   VITE_FIREBASE_MEASUREMENT_ID=your-firebase-measurement-id
   VITE_GITHUB_CLIENT_ID=your-github-oauth-client-id
   ```
4. Run the development server:
   ```bash
   npm run dev
   ```
   (Uses Vite `6.4.1`, React `19`)
   Open at: `http://localhost:5173`

---

### 📱 Mobile Frontend Setup (Kotlin + Android)
1. Open Android Studio → "Open an Existing Project"
2. Navigate to:
   ```
   frontend_mobile/AudioScholar
   ```
3. Sync Gradle files.
4. **Configure Firebase:**
   - Place `google-services.json` in `frontend_mobile/AudioScholar/app/`.
5. **Configure API Base URL:**
   In `frontend_mobile/AudioScholar/local.properties`:
   ```properties
   # Debug builds may use the Android emulator loopback URL.
   BASE_URL=http://10.0.2.2:8080/
   ```
   Release builds default to the HTTPS Render backend and should be overridden only with an HTTPS `BASE_URL`. Cleartext HTTP is disabled for release and permitted only by the debug network security config for local emulator hosts.
6. Run on an emulator or physical device.

---

## ✅ Validation Matrix

Run the smallest target-native checks for the component you changed:

```bash
# Backend
cd backend/audioscholar
./mvnw -B test
./mvnw -B spotless:check
# Optional security gate when NVD_API_KEY is configured: ./mvnw -B dependency-check:check

# Web frontend
cd frontend_web/audioscholar-app
npm ci
npm run lint
npm run test
npm run build
npm audit --audit-level=high

# Mobile frontend
cd frontend_mobile/AudioScholar
./gradlew test
./gradlew lint
./gradlew assembleDebug
# connectedAndroidTest requires an emulator or physical device
```

## 🔒 Security Notes

- Do not commit `.env`, Firebase service account JSON, `google-services.json`, API keys, OAuth secrets, or JWT secrets.
- Backend bearer/JWT values must never be logged; logs should contain user IDs and event metadata only.
- Dependency/security gates are defined in GitHub Actions and can be run locally with the commands above.

## 🧭 Example Workflow

1. Register or log in using **Firebase Authentication**.
2. Record a lecture using the mobile app. Audio is uploaded to **Nhost Storage**.
3. AI processing generates summaries and metadata in **Firebase Firestore**.
4. View the summary on web or mobile under **My Lectures**.
5. Access recommended YouTube videos for deeper learning.

---

## 🧩 Dependencies

### Backend
- **Spring Boot 3.5.8**
- **Nhost Interaction**
- **Firebase Admin SDK**
- **Google Gemini AI API**
- **YouTube Data API v3**

### Web Frontend
- **React 19**
- **Vite 6.4.1**
- **Firebase SDK**

### Mobile Frontend
- **Kotlin + Jetpack Compose**
- **AndroidX**
- **Firebase SDKs**
- **Ktor Client**
- **Media3 ExoPlayer**

---

## 🧪 Features Outside Initial Scope (Planned for Future Releases)

The following features are explicitly noted as outside the scope of the initial AudioScholar release (v1.0):

| Feature                              | Status                  |
| ------------------------------------ | ----------------------- |
| Real-time Transcription              | 🚫 Future enhancement   |
| iOS Mobile Platform Support          | 🚫 Android only for v1.0|
| Web Interface Audio Recording        | 🚫 Upload only for v1.0 |
| Multi-language Support               | 🚫 English only for v1.0|
| Background Recording (Free Users)    | 🚫 Restricted feature   |
| Recommendation Engine beyond YouTube | 🚫 Future enhancement   |

---

## 🎨 Design & Documentation

- **Use Case & Activity Diagrams**: [View on Figma](https://www.figma.com/design/5cqAE14jvnfFDlKbqHObr7/AudioScholar?node-id=24-2315&t=su6Bkd3yHO2aCleY-1)
- **Mobile Wireframes**: [View on Figma](https://www.figma.com/design/5cqAE14jvnfFDlKbqHObr7/AudioScholar?node-id=0-1&t=31ZcynnCihbXU6I4-1)  
- **Web Wireframes**: [View on Figma](https://www.figma.com/design/5cqAE14jvnfFDlKbqHObr7/AudioScholar?node-id=8-2267&t=31ZcynnCihbXU6I4-1)  
- **Database Schema & ER Diagrams**: [View on Figma](https://www.figma.com/design/5cqAE14jvnfFDlKbqHObr7/AudioScholar?node-id=24-2315&t=31ZcynnCihbXU6I4-1)

---

## 👨‍💻 Developers

**Adviser/Lead:**
- Ralph P. Laviste

**Group Adviser:**
- Jasmine A. Tulin

**Proponents:**
- Biacolo, Math Lee L.
- Terence, John Duterte
- Orlanes, John Nathan
- Barrientos, Claive Justin
- Alpez, Christian Brent

---

## ⚖️ License

Distributed under the MIT License. See `LICENSE` for more information.

---

## 📬 Contact

For issues, suggestions, or collaboration inquiries, feel free to open an issue or contact the development team.

---

✅ *AudioScholar — Empowering learners through intelligent audio insights.*