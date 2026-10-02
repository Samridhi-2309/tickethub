import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // The API is on :8080. The backend's CORS config already allows
    // :5173, so this proxy is not strictly required — but proxying
    // means the browser sees same-origin requests, which removes CORS
    // from the picture entirely during development.
    proxy: {
      '/api': 'http://localhost:8080',
      '/health': 'http://localhost:8080'
    }
  }
});
