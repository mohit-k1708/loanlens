import { ApiError } from './apiError'
import type { Paise } from '../types/loan'

/**
 * Minimal runtime validation for backend responses. Anything that does not match the contract
 * becomes an ApiError('invalid-response') instead of rendering wrong or `undefined` values.
 */

export type Obj = Record<string, unknown>

const bad = (what: string): never => {
  throw new ApiError('invalid-response', `Unexpected response shape: ${what}`)
}

export function asObject(value: unknown, what: string): Obj {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Obj) : bad(what)
}

export function asArray(value: unknown, what: string): unknown[] {
  return Array.isArray(value) ? value : bad(what)
}

export function readString(o: Obj, key: string): string {
  const v = o[key]
  return typeof v === 'string' ? v : bad(`"${key}" must be a string`)
}

export function readOptionalString(o: Obj, key: string): string | undefined {
  const v = o[key]
  if (v === undefined || v === null) return undefined
  return typeof v === 'string' ? v : bad(`"${key}" must be a string`)
}

export function readNumber(o: Obj, key: string): number {
  const v = o[key]
  return typeof v === 'number' && Number.isFinite(v) ? v : bad(`"${key}" must be a finite number`)
}

/** Rupees (JSON number) -> integer paise. */
export function readPaise(o: Obj, key: string): Paise {
  return Math.round(readNumber(o, key) * 100)
}

export function readOptionalPaise(o: Obj, key: string): Paise | undefined {
  const v = o[key]
  if (v === undefined || v === null) return undefined
  return readPaise(o, key)
}

export function readOneOf<T extends string>(o: Obj, key: string, allowed: readonly T[]): T {
  const v = readString(o, key)
  return (allowed as readonly string[]).includes(v) ? (v as T) : bad(`"${key}" has unsupported value`)
}

export function readStringArray(o: Obj, key: string): string[] {
  const v = o[key]
  if (v === undefined || v === null) return []
  return Array.isArray(v) && v.every((x) => typeof x === 'string') ? (v as string[]) : bad(`"${key}" must be a string array`)
}

export const toRupees = (paise: Paise): number => paise / 100

export function readNullableString(o: Obj, key: string): string | null {
  const v = o[key]
  if (v === undefined || v === null) return null
  return typeof v === 'string' ? v : bad(`"${key}" must be a string`)
}

/** Rupees (JSON number) or null -> integer paise or null. A missing value stays null, never 0. */
export function readNullablePaise(o: Obj, key: string): Paise | null {
  const v = o[key]
  return v === undefined || v === null ? null : readPaise(o, key)
}

export function readBoolean(o: Obj, key: string): boolean {
  const v = o[key]
  return typeof v === 'boolean' ? v : bad(`"${key}" must be a boolean`)
}

/** Look a backend enum code up in a table of UI labels; an unknown code is an invalid response. */
export function readMapped<T extends string>(o: Obj, key: string, table: Readonly<Record<string, T>>): T {
  const code = readString(o, key)
  return table[code] ?? bad(`"${key}" has unsupported value`)
}

export function readNullableMapped<T extends string>(o: Obj, key: string, table: Readonly<Record<string, T>>): T | null {
  const code = readNullableString(o, key)
  if (code === null) return null
  return table[code] ?? bad(`"${key}" has unsupported value`)
}