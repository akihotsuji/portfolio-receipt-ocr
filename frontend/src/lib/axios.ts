import axios from "axios";

const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL || "/receipt-ocr/api";
const PORTAL_API_BASE_URL =
  import.meta.env.VITE_PORTAL_API_BASE_URL || "/api";

const PORTAL_LOGIN_URL = "/portal/login";

const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: 100000,
  headers: {
    "Content-Type": "application/json",
  },
  withCredentials: true,
});

apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;

    if (
      axios.isAxiosError(error) &&
      error.response?.status === 401 &&
      !originalRequest._retry
    ) {
      originalRequest._retry = true;

      try {
        await axios.post(`${PORTAL_API_BASE_URL}/auth/refresh`, null, {
          withCredentials: true,
        });

        return apiClient(originalRequest);
      } catch {
        window.location.href = PORTAL_LOGIN_URL;
        return Promise.reject(error);
      }
    }

    return Promise.reject(error);
  },
);

export default apiClient;
