import { ApiError } from '../api/client.ts';

/** A customer-facing sentence for errors that pages do not handle specifically. */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.isNetworkError) return 'We could not reach Cinéva. Check that the app is running and try again.';
    if (error.status === 404) return 'We could not find what you were looking for.';
    if (error.status === 401) return 'Please sign in to continue.';
    if (error.status === 403) return 'Your account is not allowed to do that.';
    if (error.status >= 500) return 'Something went wrong on our side. Please try again.';
    return error.message;
  }
  return 'Something unexpected happened. Please try again.';
}

export function isApiError(error: unknown, code: string): error is ApiError {
  return error instanceof ApiError && error.code === code;
}
