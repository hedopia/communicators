export {};

declare global {
  interface Window {
    APP_CONFIG: {
      appBasePath: string;
    };
  }
}
