package org.openbridge.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpParserTest {
    @Test
    fun extractsPersianBankOtp() {
        val sms = "بانک ملت\nرمز پویا: ۱۲۳۴۵۶\nمبلغ: 500,000 ریال"
        assertEquals("123456", OtpParser.extractOtp(sms))
    }

    @Test
    fun extractsEnglishOtp() {
        val sms = "Your verification code is 849201. Do not share it."
        assertEquals("849201", OtpParser.extractOtp(sms))
    }

    @Test
    fun ignoresPlainBalanceSmsWithoutOtp() {
        val sms = "برداشت از حساب: 250000 ریال\nمانده: 1450000 ریال\n1405/09/04"
        assertNull(OtpParser.extractOtp(sms))
    }
}
