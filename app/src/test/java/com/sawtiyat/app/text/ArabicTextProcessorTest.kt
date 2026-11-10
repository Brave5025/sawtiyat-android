package com.sawtiyat.app.text

import org.junit.Assert.assertEquals
import org.junit.Test

class ArabicTextProcessorTest {
    @Test
    fun convertsArabicIndicCardinalNumbers() {
        assertEquals("لدي اثنا عشر كتابًا", ArabicTextProcessor.normalizeForTts("لدي ١٢ كتابًا"))
    }

    @Test
    fun preservesPhoneNumbersAndLeadingZeroIdentifiers() {
        assertEquals(
            "اتصل على صفر خمسة صفر واحد اثنان ثلاثة أربعة خمسة ستة سبعة",
            ArabicTextProcessor.normalizeForTts("اتصل على ٠٥٠١٢٣٤٥٦٧")
        )
        assertEquals("الرمز صفر صفر واحد اثنان ثلاثة", ArabicTextProcessor.normalizeForTts("الرمز 00123"))
    }

    @Test
    fun expandsPercentagesAndTimes() {
        assertEquals("النسبة خمسة وعشرون بالمئة", ArabicTextProcessor.normalizeForTts("النسبة 25٪"))
        assertEquals("الموعد الساعة الثامنة والنصف", ArabicTextProcessor.normalizeForTts("الموعد 08:30"))
        assertEquals("الموعد الساعة الحادية عشرة والربع مساء", ArabicTextProcessor.normalizeForTts("الموعد 23:15"))
    }

    @Test
    fun expandsValidIsoDateButDoesNotInventInvalidCalendarDate() {
        assertEquals(
            "التاريخ التاسع والعشرون من شهر فبراير عام ألفان وأربعة وعشرون",
            ArabicTextProcessor.normalizeForTts("التاريخ 2024-02-29")
        )
        assertEquals(
            "التاريخ 2024-02-30",
            ArabicTextProcessor.normalizeForTts("التاريخ 2024-02-30")
        )
    }

    @Test
    fun expandsCurrenciesAndGroupedNumbers() {
        assertEquals("سعره اثنا عشر دولار", ArabicTextProcessor.normalizeForTts("سعره $12"))
        assertEquals("المجموع ألف", ArabicTextProcessor.normalizeForTts("المجموع 1,000"))
    }

    @Test
    fun preservesValuesOutsideSupportedRangeAndExpandsCommonAbbreviations() {
        assertEquals("المبلغ 1000000", ArabicTextProcessor.normalizeForTts("المبلغ 1000000"))
        assertEquals("على سبيل المثال ثلاثة", ArabicTextProcessor.normalizeForTts("e.g. 3"))
        assertEquals("درجة الحرارة سالب خمسة", ArabicTextProcessor.normalizeForTts("درجة الحرارة -5"))
        assertEquals("المسافة خمسة كيلومتر", ArabicTextProcessor.normalizeForTts("المسافة 5 km"))
        assertEquals("ملف بي دي إف", ArabicTextProcessor.normalizeForTts("ملف PDF"))
    }
}
