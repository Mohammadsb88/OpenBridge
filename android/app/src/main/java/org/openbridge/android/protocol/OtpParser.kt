package org.openbridge.android.protocol

/**
 * Extracts 4-8 digit OTP codes from English and Persian SMS messages.
 */
object OtpParser {
    private val KEYWORD_OTP_REGEX = Regex(
        pattern = "(?:رمز\\s*(?:پویا|یکبار\\s*مصرف|ورود|تایید|تأیید)?|کد\\s*(?:تایید|تأیید|ورود|فعالسازی|فعال‌سازی)?|otp|code|pin|passcode|verification)(?:\\s+(?:is|شما|عبارت\\s*است\\s*از))?[\\s:：\\-–]*([0-9]{4,8})",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val STANDALONE_CODE_REGEX = Regex("(?<![0-9/,\\-]|(?:[0-9]\\.))([0-9]{4,8})(?![0-9/,\\-]|(?:\\.[0-9]))")

    private val NON_OTP_CONTEXT_REGEX = Regex(
        "(?:ریال|تومان|مانده|موجودی|مبلغ|واریز|برداشت|کارت|شبا|حساب|تلفن|تماس|IRR|TMN|Rial)",
        RegexOption.IGNORE_CASE
    )

    fun normalizeDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in '۰'..'۹' -> sb.append(('0'.code + (ch.code - '۰'.code)).toChar())
                in '٠'..'٩' -> sb.append(('0'.code + (ch.code - '٠'.code)).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun extractOtp(rawBody: String): String? {
        if (rawBody.isBlank()) return null
        val text = normalizeDigits(rawBody)

        // 1. High-confidence match near OTP/verification keywords
        KEYWORD_OTP_REGEX.find(text)?.let { match ->
            return match.groupValues[1]
        }

        // 2. Line-by-line fallback if the SMS explicitly mentions OTP keywords anywhere
        val lower = text.lowercase()
        val hasOtpKeyword = listOf(
            "رمز", "کد", "otp", "code", "verification", "verify", "passcode", "pin", "تایید", "تأیید"
        ).any { lower.contains(it) }

        if (!hasOtpKeyword) return null

        for (line in text.lines()) {
            if (NON_OTP_CONTEXT_REGEX.containsMatchIn(line) && !line.contains("رمز") && !line.contains("کد")) {
                continue
            }
            val match = STANDALONE_CODE_REGEX.find(line)
            if (match != null) {
                val candidate = match.groupValues[1]
                // Avoid Iranian years like 1403..1410 unless explicitly labeled earlier
                if (candidate.length == 4 && (candidate.startsWith("140") || candidate.startsWith("139"))) {
                    continue
                }
                return candidate
            }
        }
        return null
    }
}
