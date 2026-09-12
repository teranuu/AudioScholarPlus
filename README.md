# 🎓 AudioScholar: Transforming Audio into Actionable Insights for Learners

![Build Status](https://img.shields.io/badge/build-passing-brightgreen)
![License](https://img.shields.io/badge/license-MIT-blue)
![Version](https://img.shields.io/badge/version-1.0.0-orange)

AudioScholar is an intelligent, multi-user platform designed to record lecture audio and leverage AI-driven summarization techniques to produce structured insights for learners. As a dual-platform solution comprising an Android mobile application and a comprehensive web interface, it allows users to capture, summarize, and receive personalized learning material recommendations based on audio recordings. By transforming lengthy lectures into digestible key points, AudioScholar enhances note-taking efficiency and content comprehension.

<p align="center">
<img src="logo\AudioScholarLogoNoBG.png" alt="AudioScholar Logo" width="250"/>
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
- **Node.js** (v16+)
- **npm** or **yarn**
- **Git**
- **Maven** (for backend)
- **Android Studio** (Latest version)
- **Nhost Account** (for cloud file storage - [Nhost Cloud](https://nhost.io/))
- **Firebase Account** (for authentication and Firestore database - [Firebase Console](https://console.firebase.google.com/))

---

### 📁 Cloning the Repository
```bash
git clone https://github.com/IT342-G3-AudioScholar/AudioScholar.git
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
   # Nhost Storage Configuration
   NHOST_ADMIN_SECRET=your-nhost-admin-secret # Used by Spring Boot to access Nhost Storage

   # API Keys
   GEMINI_API_KEY=your-gemini-api-key
   YOUTUBE_API_KEY=your-youtube-api-key
   # Other secrets as needed
   # CONVERTAPI_SECRET=your-convertapi-secret
   # UPTIME_ROBOT_API_TOKEN=your-uptimerobot-v3-api-token
   # UPTIME_ROBOT_MONITOR_URL_FILTER=your-render-backend-hostname
   ```

4. **Configure Application Properties:**
   In `backend/audioscholar/src/main/resources/application.properties`:
   - Ensure `nhost.storage.url` is correctly set.
   - Configure Firebase properties:
     ```properties
     spring.cloud.gcp.project-id=your-firebase-project-id
     firebase.service-account.file=firebase-service-account.json
     ```

5. Run the backend:
   ```bash
   mvn spring-boot:run
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
   npm install
   ```
3. Create a `.env` file in `frontend_web/audioscholar-app`:
   ```dotenv
   # Backend API URL
   VITE_API_URL=http://localhost:8080

   # Firebase Frontend Configuration
   VITE_FIREBASE_API_KEY=your-firebase-api-key
   VITE_FIREBASE_AUTH_DOMAIN=your-firebase-auth-domain
   VITE_FIREBASE_PROJECT_ID=your-firebase-project-id
   VITE_FIREBASE_STORAGE_BUCKET=your-firebase-storage-bucket
   VITE_FIREBASE_MESSAGING_SENDER_ID=your-firebase-messaging-sender-id
   VITE_FIREBASE_APP_ID=your-firebase-app-id
   ```
4. Run the development server:
   ```bash
   npm run dev
   ```
   (Uses Vite `6.4.1`, React `19`)
   Open at: `http://localhost:3000`

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
   API_BASE_URL=http://your-local-network-ip:8080
   ```
6. Run on an emulator or physical device.

---

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
