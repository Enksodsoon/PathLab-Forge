import { readdir, stat } from 'node:fs/promises'

const root = new URL('../../build/frontend/web/', import.meta.url)
const files = (await readdir(root)).filter((name) => name.endsWith('.js'))
const sizes = await Promise.all(files.map(async (name) => ({
  name,
  bytes: (await stat(new URL(name, root))).size,
})))
const entry = sizes.find((item) => item.name === 'app.js')
if (!entry || entry.bytes > 300_000) {
  throw new Error(`Forge entry bundle exceeds 300000 bytes: ${entry?.bytes ?? 'missing'}`)
}
const oversized = sizes.find((item) => item.bytes > 400_000)
if (oversized) {
  throw new Error(`${oversized.name} exceeds the 400000-byte chunk budget`)
}
console.log(`Bundle budget passed: ${sizes.map((item) => `${item.name}=${item.bytes}`).join(', ')}`)
