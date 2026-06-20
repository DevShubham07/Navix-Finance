import { config } from "@/lib/config";

/**
 * Generic typed fetch wrapper around the Spring Boot backend
 * (BACKEND_BASE_URL). Used by server-side route handlers (BFF).
 *
 * Client components should call the Next.js BFF (NEXT_PUBLIC_API_BASE_URL),
 * NOT the backend directly.
 */

export interface ApiErrorBody {
  message: string;
  code?: string;
  details?: unknown;
}

export class ApiError extends Error {
  readonly status: number;
  readonly body?: ApiErrorBody;

  constructor(status: number, message: string, body?: ApiErrorBody) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.body = body;
  }
}

export interface RequestOptions extends Omit<RequestInit, "body"> {
  /** JSON-serializable request body. */
  body?: unknown;
  /** Base URL override; defaults to the backend base URL. */
  baseUrl?: string;
}

/**
 * Perform a JSON request and parse/validate the response.
 * Throws {@link ApiError} on non-2xx responses.
 */
export async function apiFetch<T>(
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const { body, baseUrl, headers, ...rest } = options;
  const url = `${baseUrl ?? config.backendBaseUrl}${path}`;

  const res = await fetch(url, {
    ...rest,
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  const text = await res.text();
  const parsed = text ? (JSON.parse(text) as unknown) : undefined;

  if (!res.ok) {
    const errBody = parsed as ApiErrorBody | undefined;
    throw new ApiError(
      res.status,
      errBody?.message ?? `Request failed with status ${res.status}`,
      errBody,
    );
  }

  return parsed as T;
}

export const apiClient = {
  get: <T>(path: string, options?: RequestOptions) =>
    apiFetch<T>(path, { ...options, method: "GET" }),
  post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    apiFetch<T>(path, { ...options, method: "POST", body }),
  put: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    apiFetch<T>(path, { ...options, method: "PUT", body }),
  patch: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    apiFetch<T>(path, { ...options, method: "PATCH", body }),
  delete: <T>(path: string, options?: RequestOptions) =>
    apiFetch<T>(path, { ...options, method: "DELETE" }),
};
