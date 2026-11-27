// Loaded before linkedom: `entities` decodes its tables with atob at module load.
const B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
globalThis.atob ??= s => {
  s = String(s).replace(/[^A-Za-z0-9+/]/g, "")
  let out = "", bits = 0, acc = 0
  for (const c of s) { acc = (acc << 6) | B64.indexOf(c); bits += 6; if (bits >= 8) { bits -= 8; out += String.fromCharCode((acc >> bits) & 255) } }
  return out
}
