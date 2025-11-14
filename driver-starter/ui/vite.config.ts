import { defineConfig } from "vite";
import react from "@vitejs/plugin-react-swc";

export default defineConfig(({ mode }) => ({
  plugins: [
    react(),
    {
      name: "html-transform",
      transformIndexHtml(html) {
        if (mode === "development") {
          return html.replace("__APP_BASE_PATH__", "/driver");
        }
        return html;
      },
    },
  ],
  build: {
    outDir: "../src/main/resources/static",
    emptyOutDir: true,
  },
  server: {
    proxy: {
      "/driver": "http://localhost:4001",
      "/cluster": "http://localhost:4001",
    },
  },
}));
