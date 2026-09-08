package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class RedactTest {

    @Test
    fun keepsOnlyTheLastFourDigits() {
        assertEquals("…0147", maskNumber("+1 (555) 555-0147"))
        assertEquals("…0147", maskNumber("5555550147"))
    }

    @Test
    fun formattingCannotSmuggleAShortNumberThrough() {
        assertEquals("****", maskNumber("+1 (12)"))
        assertEquals("****", maskNumber("---4---"))
    }

    @Test
    fun shortCodesAreNotEchoed() {
        assertEquals("****", maskNumber("6789"))
        assertEquals("****", maskNumber("911"))
        assertEquals("****", maskNumber(""))
    }

    @Test
    fun fiveDigitsIsTheFirstLengthThatReveals() {
        assertEquals("…6789", maskNumber("56789"))
    }
}
