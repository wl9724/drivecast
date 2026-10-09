export const util = {
  TextEncoder: class { encodeInto(s: string) { return new Uint8Array(Buffer.from(s, 'utf8')); } },
  TextDecoder: { create(e: string) { return { decodeToString(b: Uint8Array) { return Buffer.from(b).toString('utf8'); } }; } },
};
