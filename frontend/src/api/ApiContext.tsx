import { createContext, useContext, type ReactNode } from 'react';
import type { Api } from './endpoints';

const ApiContext = createContext<Api | null>(null);

/**
 * Dependency injection for the API: the app provides the real client, tests provide a fake one. Components ask for
 * `useApi()` instead of importing a module-level singleton.
 */
export function ApiProvider({ api, children }: { api: Api; children: ReactNode }) {
  return <ApiContext.Provider value={api}>{children}</ApiContext.Provider>;
}

/** The API client provided by the nearest `ApiProvider`. */
export function useApi(): Api {
  const api = useContext(ApiContext);
  if (!api) throw new Error('useApi must be used inside <ApiProvider>');
  return api;
}
