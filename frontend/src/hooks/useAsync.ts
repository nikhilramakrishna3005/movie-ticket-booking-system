import { useCallback, useEffect, useRef, useState, type DependencyList } from 'react';

export type AsyncState<T> =
  | { status: 'loading'; data: T | undefined; error: null }
  | { status: 'success'; data: T; error: null }
  | { status: 'error'; data: T | undefined; error: unknown };

/**
 * Runs {@code load} whenever {@code deps} change and ignores responses that arrive after a newer
 * request started, so a slow earlier search can never overwrite a newer one. Previous data is
 * kept while reloading to avoid layout flashes.
 */
export function useAsync<T>(load: () => Promise<T>, deps: DependencyList) {
  const [state, setState] = useState<AsyncState<T>>({ status: 'loading', data: undefined, error: null });
  const generation = useRef(0);
  const loadRef = useRef(load);
  loadRef.current = load;

  const run = useCallback(() => {
    const current = ++generation.current;
    setState((previous) => ({ status: 'loading', data: previous.data, error: null }));
    loadRef.current().then(
      (data) => {
        if (current === generation.current) setState({ status: 'success', data, error: null });
      },
      (error: unknown) => {
        if (current === generation.current) setState((previous) => ({ status: 'error', data: previous.data, error }));
      },
    );
  }, []);

  useEffect(run, deps);

  useEffect(() => () => {
    generation.current++;
  }, []);

  return { ...state, reload: run, setData: (data: T) => setState({ status: 'success', data, error: null }) };
}
