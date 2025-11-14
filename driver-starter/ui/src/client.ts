import axios from "axios";

const { appBasePath } = window.APP_CONFIG;

export const api = axios.create({
  baseURL: appBasePath,
});
