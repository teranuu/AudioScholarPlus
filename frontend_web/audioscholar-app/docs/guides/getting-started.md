# Getting Started

This guide will help you set up the AudioScholar frontend development environment and get the application running on your local machine.

## Prerequisites

Before you begin, ensure you have the following installed:

- **Node.js v18+**: The application is built with React and Vite. Use the current LTS release or newer.
- **npm** (or yarn/pnpm): The package manager comes with Node.js.

## Installation

1.  **Clone the repository** (if you haven't already):
    ```bash
    git clone https://github.com/MasuRii/AudioScholar.git
    cd AudioScholar/frontend_web/audioscholar-app
    ```

2.  **Install dependencies**:
    Navigate to the project directory and run:
    ```bash
    npm ci
    ```
    This command reads `package.json` and installs all necessary packages, including:
    - React & React DOM
    - React Router DOM
    - Firebase
    - Tailwind CSS
    - Axios

## Running the Application

To start the local development server:

```bash
npm run dev
```

This command runs `vite` and usually starts the app at `http://localhost:5173`. The terminal output will show the exact URL. Open this URL in your browser to see the application.

## Building for Production

To create a production-ready build:

```bash
npm run build
```

This command runs `vite build`, which compiles the application into static files in the `dist/` directory, optimized for performance.

## Previewing the Production Build

To preview the production build locally:

```bash
npm run preview
```

This command runs `vite preview` and serves the contents of the `dist/` folder, allowing you to test the built application before deploying.

## Linting

To check for code quality issues:

```bash
npm run lint
```

This command runs `eslint .` to analyze your code based on the rules defined in `eslint.config.js`.


## Testing and audit

```bash
npm run test
npm audit --audit-level=high
```