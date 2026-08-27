import axios from "axios";

const { appBasePath, clusterBasePath: injectedClusterBasePath } = window.APP_CONFIG;

/** driver REST API base path (e.g. "/driver"), injected by the server into index.html */
export const driverBasePath = appBasePath;

/** cluster REST API base path (e.g. "/cluster"), injected by the server into index.html */
export const clusterBasePath = injectedClusterBasePath;

/** axios instance for driver REST API ({driverBasePath}/...) */
export const api = axios.create({
  baseURL: appBasePath,
});

/** axios instance for server-root paths (/cluster/..., /redirect-to-index/...) */
export const rootApi = axios.create({
  baseURL: "",
});

/** path routed to a specific cluster node (e.g. /redirect-to-index/2/cluster/node-status) */
export const nodePath = (nodeIndex: number | string, path: string) =>
  `/redirect-to-index/${nodeIndex}${path}`;

/** driver path routed to a specific cluster node (e.g. /redirect-to-index/2/driver/device-status) */
export const nodeDriverPath = (nodeIndex: number | string, path: string) =>
  nodePath(nodeIndex, `${appBasePath}${path}`);
