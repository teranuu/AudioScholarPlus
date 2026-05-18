# AudioScholar Frontend

Welcome to the **AudioScholar** frontend repository. This is a React-based application built with Vite and Tailwind CSS, designed to provide a seamless interface for audio analysis, management, and subscription services.

## 📚 Documentation

We maintain comprehensive documentation to help developers understand, contribute to, and deploy the application.

👉 **[Go to Documentation Index](./docs/README.md)**

### Key Documentation Sections

*   **[Getting Started](./docs/guides/getting-started.md)**: Setup, installation, and running locally.
*   **[Project Structure](./docs/guides/project-structure.md)**: How the codebase is organized.
*   **[Style Guide](./docs/guides/style-guide.md)**: Coding standards and UI conventions.
*   **[Deployment](./docs/guides/deployment.md)**: Build and deployment instructions.

## 🚀 Quick Start

Follow these steps to get the application running on your local machine.

### Prerequisites

*   Node.js (v18 or higher)
*   npm (usually comes with Node.js)

### Installation

1.  **Install dependencies:**

    ```bash
    npm ci
    ```

2.  **Copy environment template:**

    ```bash
    cp .env.example .env
    ```

3.  **Start the development server:**

    ```bash
    npm run dev
    ```

4.  **Open the application:**
    Open your browser and navigate to `http://localhost:5173` (or the port shown in your terminal).


### Validation

```bash
npm run lint
npm run test
npm run build
npm audit --audit-level=high
```

## 🛠️ Tech Stack

*   **Core Framework:** [React](https://reactjs.org/) + [Vite](https://vitejs.dev/)
*   **Styling:** [Tailwind CSS](https://tailwindcss.com/)
*   **Routing:** [React Router](https://reactrouter.com/)
*   **State Management:** React Context API
*   **Backend Services:** Firebase (Auth, Firestore)

## 📄 License

Distributed under the MIT License. See `../../LICENSE` for details.