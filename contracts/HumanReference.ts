/** SEQUO display reference v1. Mirrors the common Kotlin HumanReference codec. */
export const REFERENCE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
export const referencePrefixes = {
  CHECKOUT: "H", ORDER: "C", MERCHANT_ORDER: "M", DELIVERY: "D", PARCEL: "P",
  RETURN: "R", GROUP: "G", USER: "A", MERCHANT: "V", COURIER: "T", RELAY: "S",
  PRODUCT: "B", FINANCIAL: "F", INSTRUCTION: "N", LOCKER: "K", BARGAINING: "E", PACKAGE: "J",
  LEDGER: "W", COOPERATIVE: "Q",
} as const;
export type PublicReferenceKind = keyof typeof referencePrefixes;

function checksum(symbols: string): number {
  let crc = 0;
  for (const symbol of symbols) {
    const value = REFERENCE_ALPHABET.indexOf(symbol);
    for (let bit = 4; bit >= 0; bit--) {
      const feedback = ((crc >>> 9) ^ (value >>> bit)) & 1;
      crc = (crc << 1) & 0x3ff;
      if (feedback) crc ^= 0x233;
    }
  }
  return crc;
}
const format = (symbols: string): string => symbols.match(/.{3}/g)!.join("-");

export function encodeReference(kind: PublicReferenceKind, payload: string): string {
  if (payload.length !== 6 || [...payload].some((c) => !REFERENCE_ALPHABET.includes(c)))
    throw new Error("Six canonical base32 symbols are required.");
  const data = referencePrefixes[kind] + payload;
  const crc = checksum(data);
  return format(data + REFERENCE_ALPHABET[crc >>> 5] + REFERENCE_ALPHABET[crc & 31]);
}

/** Checks transcription, not existence or authorization. No network request is made. */
export function normalizeReference(input: string): string | null {
  if (input.length > 64) return null;
  // ASCII only: don't silently accept Unicode lookalikes or control characters.
  const symbols = input.replace(/[ -]/g, "").replace(/[a-z]/g, (c) => c.toUpperCase())
    .replace(/O/g, "0").replace(/[IL]/g, "1");
  if (symbols.length !== 9 || [...symbols].some((c) => !REFERENCE_ALPHABET.includes(c))) return null;
  if (!Object.values(referencePrefixes).some((prefix) => prefix === symbols[0])) return null;
  const crc = checksum(symbols.slice(0, 7));
  if (REFERENCE_ALPHABET[crc >>> 5] !== symbols[7] || REFERENCE_ALPHABET[crc & 31] !== symbols[8]) return null;
  return format(symbols);
}

export function isValidReference(input: string, kind?: PublicReferenceKind): boolean {
  const canonical = normalizeReference(input);
  return canonical !== null && (kind === undefined || canonical[0] === referencePrefixes[kind]);
}
