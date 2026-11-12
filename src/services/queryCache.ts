/**
 * Tiny request cache + in-flight de-duplication, so that several components (or several screens)
 * asking for the same resource trigger ONE request and always see the same numbers.
 * Failed requests are never cached. Any mutation calls invalidateAll().
 */

interface Entry {
  promise: Promise<unknown>
  at: number
}

const TTL_MS = 30_000
const entries = new Map<string, Entry>()

export function cachedRequest<T>(key: string, fetcher: () => Promise<T>): Promise<T> {
  const hit = entries.get(key)
  if (hit && Date.now() - hit.at < TTL_MS) return hit.promise as Promise<T>

  const promise = fetcher()
  const entry: Entry = { promise, at: Date.now() }
  entries.set(key, entry)
  promise.catch(() => {
    if (entries.get(key) === entry) entries.delete(key)
  })
  return promise
}

export function invalidateKey(key: string): void {
  entries.delete(key)
}

export function invalidateAll(): void {
  entries.clear()
}
