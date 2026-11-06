package com.ideasdeveloper.idc.server.auth.application

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplicationUserCodesTest {
    @Test
    fun generatedCodesUseTheUserPrefixAndGuestCodeAlphabet() {
        repeat(200) {
            val code = ApplicationUserCodes.generate()
            assertTrue(code.startsWith("us"))
            assertTrue(ApplicationUserCodes.isValid(code))
        }
    }

    @Test
    fun validationIgnoresCaseAndSurroundingWhitespace() {
        assertTrue(ApplicationUserCodes.isValid(" US23456789 "))
        assertFalse(ApplicationUserCodes.isValid("23456789"))
        assertFalse(ApplicationUserCodes.isValid("us23456780"))
        assertFalse(ApplicationUserCodes.isValid("us2345678"))
    }
}
