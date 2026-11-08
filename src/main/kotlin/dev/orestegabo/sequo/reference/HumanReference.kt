package dev.orestegabo.sequo.reference

/** Display references, never database keys, authentication or handoff credentials. */
enum class PublicReferenceKind(val prefix: Char) {
    CHECKOUT('H'), ORDER('C'), MERCHANT_ORDER('M'), DELIVERY('D'), PARCEL('P'),
    RETURN('R'), GROUP('G'), USER('A'), MERCHANT('V'), COURIER('T'), RELAY('S'),
    PRODUCT('B'), FINANCIAL('F'), INSTRUCTION('N'), LOCKER('K'), BARGAINING('E'), PACKAGE('J'),
    LEDGER('W'), COOPERATIVE('Q');
}

/**
 * SEQUO reference v1: type + six random base32 symbols + two CRC symbols, grouped 3-3-3.
 * CRC-10/ATM (poly 0x233, init/xorout 0, MSB first), over the seven 5-bit symbols.
 * A correct checksum checks transcription only. The server must still check existence and access.
 * Keep this platform-independent file identical in the API and the four mobile apps.
 */
object HumanReference {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val SYMBOL_COUNT = 9

    fun encode(kind: PublicReferenceKind, payload: String): String {
        require(payload.length == 6 && payload.all { it in ALPHABET }) { "Six canonical base32 symbols are required." }
        val data = kind.prefix + payload
        val crc = checksum(data)
        return format(data + ALPHABET[crc shr 5] + ALPHABET[crc and 31])
    }

    /** Accepts ASCII spaces, hyphens, lowercase, and Crockford's O/0 and I/L/1 aliases. */
    fun normalize(input: String): String? {
        if (input.length > 64) return null
        val symbols = buildString {
            for (character in input) {
                if (character == ' ' || character == '-') continue
                val upper = if (character in 'a'..'z') character - 32 else character
                append(when (upper) { 'O' -> '0'; 'I', 'L' -> '1'; else -> upper })
            }
        }
        if (symbols.length != SYMBOL_COUNT || symbols.any { it !in ALPHABET }) return null
        if (PublicReferenceKind.entries.none { it.prefix == symbols.first() }) return null
        val crc = checksum(symbols.take(7))
        if (ALPHABET[crc shr 5] != symbols[7] || ALPHABET[crc and 31] != symbols[8]) return null
        return format(symbols)
    }

    fun isValid(input: String, kind: PublicReferenceKind? = null): Boolean {
        val canonical = normalize(input) ?: return false
        return kind == null || canonical.first() == kind.prefix
    }

    fun kind(input: String): PublicReferenceKind? = normalize(input)?.first()?.let { prefix ->
        PublicReferenceKind.entries.firstOrNull { it.prefix == prefix }
    }

    private fun format(symbols: String) = symbols.chunked(3).joinToString("-")

    private fun checksum(data: String): Int {
        var crc = 0
        for (symbol in data) {
            val value = ALPHABET.indexOf(symbol)
            for (bit in 4 downTo 0) {
                val feedback = ((crc shr 9) xor (value shr bit)) and 1
                crc = (crc shl 1) and 0x3ff
                if (feedback == 1) crc = crc xor 0x233
            }
        }
        return crc
    }
}
