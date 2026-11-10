package com.sawtiyat.app.text

import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

/**
 * Conservative, context-free text normalization for Arabic TTS.
 * This is a small readability aid, not a full Arabic grammar or linguistic engine.
 */
object ArabicTextProcessor {
    private val datePattern = Regex("(?<![\\d])(?:([0-9]{4})[-/.]([0-9]{1,2})[-/.]([0-9]{1,2})|([0-9]{1,2})[-/.]([0-9]{1,2})[-/.]([0-9]{4}))(?![\\d])")
    private val timePattern = Regex("(?<![\\d:])([01]?[0-9]|2[0-3]):([0-5][0-9])(?![\\d:])")
    private val phoneLikePattern = Regex("(?<![\\d])\\+?\\d(?:[\\d\\s,().-]*\\d)?(?![\\d])")
    private val currencyPattern = Regex(
        "(?<![\\p{L}\\d])(?:([$€£¥])\\s*(\\d{1,6})(?:\\.(\\d{1,2}))?|(\\d{1,6})(?:\\.(\\d{1,2}))?\\s*(USD|EUR|GBP|SAR|AED|KWD|دولار(?:\\s+أمريكي)?|يورو|جنيه(?:\\s+إسترليني)?|ريال(?:\\s+سعودي)?|درهم(?:\\s+إماراتي)?|دينار(?:\\s+كويتي)?))(?![\\p{L}\\d])",
        RegexOption.IGNORE_CASE
    )
    private val percentPattern = Regex("(?<![\\d])([0-9]+(?:\\.[0-9]+)?)\\s*(%|٪|％)(?![\\p{L}])")
    private val negativeNumberPattern = Regex("(?<![\\p{L}\\d_])[−-]([0-9]+(?:\\.[0-9]+)?)(?![\\p{L}\\d_])")
    private val decimalOrIntegerPattern = Regex("(?<![\\p{L}\\d_])[0-9]+(?:\\.[0-9]+)?(?![\\p{L}\\d_])")

    private val months = listOf(
        "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
        "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"
    )

    private val abbreviations = listOf(
        Regex("(?i)(?<![\\p{L}])e\\.g\\.") to "على سبيل المثال",
        Regex("(?i)(?<![\\p{L}])i\\.e\\.") to "أي",
        Regex("(?i)(?<![\\p{L}])etc\\.") to "إلى آخره",
        Regex("(?i)(?<![\\p{L}])dr\\.") to "دكتور",
        Regex("(?i)(?<![\\p{L}])mr\\.") to "السيد",
        Regex("(?i)(?<![\\p{L}])mrs\\.") to "السيدة",
        Regex("(?i)(?<![\\p{L}])prof\\.") to "الأستاذ",
        Regex("(?i)(?<![\\p{L}])no\\.") to "رقم"
    )

    /** Normalize Arabic/Persian digits and read clearly recognizable numbers aloud. */
    fun normalizeForTts(text: String): String {
        var result = normalizeDigits(text)
        // Remove only conventional three-digit grouping separators, not ordinary commas.
        result = Regex("(?<=\\d),(?=\\d{3}(?:\\D|$))").replace(result, "")
        val protected = mutableListOf<String>()

        result = replaceDates(result, protected)
        result = replaceTimes(result)
        result = protectPhoneLikeSequences(result, protected)
        result = replaceCurrencyAmounts(result)
        result = replacePercentages(result)
        result = replaceNegativeNumbers(result)
        result = replaceAbbreviationsAndSymbols(result)
        result = replaceRemainingNumbers(result)

        protected.forEachIndexed { index, original ->
            result = result.replace(placeholder(index), original)
        }
        return result.replace(Regex("[ \\t]+"), " ").trim()
    }

    /** Converts any supported Arabic-Indic or Persian digits to ASCII for predictable parsing. */
    private fun normalizeDigits(text: String): String = buildString(text.length) {
        text.forEach { char ->
            when (char) {
                in '٠'..'٩' -> append(('0'.code + (char.code - '٠'.code)).toChar())
                in '۰'..'۹' -> append(('0'.code + (char.code - '۰'.code)).toChar())
                '٫' -> append('.')
                '٬' -> append(',')
                '％' -> append('%')
                else -> append(char)
            }
        }
    }

    private fun replaceDates(text: String, protected: MutableList<String>): String = datePattern.replace(text) { match ->
        val (year, month, day) = if (match.groupValues[1].isNotEmpty()) {
            Triple(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
        } else {
            Triple(match.groupValues[6].toInt(), match.groupValues[5].toInt(), match.groupValues[4].toInt())
        }
        val date = try {
            LocalDate.of(year, month, day)
        } catch (_: DateTimeException) {
            val index = protected.size
            protected += match.value
            return@replace placeholder(index)
        }
        val monthName = months[date.monthValue - 1]
        "${ordinalDay(date.dayOfMonth)} من شهر $monthName عام ${numberToWords(date.year)}"
    }

    private fun replaceTimes(text: String): String = timePattern.replace(text) { match ->
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        val hour12 = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
        val hourWords = when (hour12) {
            0 -> "الثانية عشرة"
            1 -> "الواحدة"
            2 -> "الثانية"
            3 -> "الثالثة"
            4 -> "الرابعة"
            5 -> "الخامسة"
            6 -> "السادسة"
            7 -> "السابعة"
            8 -> "الثامنة"
            9 -> "التاسعة"
            10 -> "العاشرة"
            11 -> "الحادية عشرة"
            else -> "الثانية عشرة"
        }
        val minutes = when (minute) {
            0 -> "تمامًا"
            15 -> "والربع"
            30 -> "والنصف"
            45 -> "إلا الربع"
            else -> "و${numberToWords(minute)} دقيقة"
        }
        val period = when (hour) {
            0 -> "بعد منتصف الليل"
            in 13..23 -> "مساء"
            else -> ""
        }
        "الساعة $hourWords $minutes $period".trim()
    }

    /** Preserve phone-like digit strings (including leading zeroes and grouped numbers). */
    private fun protectPhoneLikeSequences(text: String, protected: MutableList<String>): String =
        phoneLikePattern.replace(text) { match ->
            val digits = match.value.filter(Char::isDigit)
            val hasSeparators = match.value.any { !it.isDigit() && it != '+' }
            val isPhoneOrIdentifier = digits.length > 1 && digits.startsWith('0') ||
                digits.length >= 7 && (hasSeparators || match.value.startsWith('+'))
            if (isPhoneOrIdentifier) {
                val index = protected.size
                protected += spellDigitSequence(match.value)
                placeholder(index)
            } else {
                match.value
            }
        }

    private fun replaceCurrencyAmounts(text: String): String = currencyPattern.replace(text) { match ->
        val symbol = match.groupValues[1]
        val prefixInteger = match.groupValues[2]
        val prefixFraction = match.groupValues[3]
        val suffixInteger = match.groupValues[4]
        val suffixFraction = match.groupValues[5]
        val suffixCurrency = match.groupValues[6]
        val integer = (prefixInteger.ifEmpty { suffixInteger }).toIntOrNull()
            ?: return@replace match.value
        val fraction = prefixFraction.ifEmpty { suffixFraction }
        val currency = currencyName(symbol, suffixCurrency)
        val amountWords = if (fraction.isEmpty()) {
            numberToWords(integer)
        } else {
            "${numberToWords(integer)} فاصلة ${digitsToWords(fraction)}"
        }
        "$amountWords $currency"
    }

    private fun currencyName(symbol: String, codeOrName: String): String {
        if (symbol.isNotEmpty()) return when (symbol) {
            "\$" -> "دولار"
            "€" -> "يورو"
            "£" -> "جنيه إسترليني"
            else -> "ين ياباني"
        }
        return when (codeOrName.uppercase(Locale.ROOT)) {
            "USD" -> "دولار"
            "EUR" -> "يورو"
            "GBP" -> "جنيه إسترليني"
            "SAR" -> "ريال سعودي"
            "AED" -> "درهم إماراتي"
            "KWD" -> "دينار كويتي"
            else -> codeOrName
        }
    }

    private fun replacePercentages(text: String): String = percentPattern.replace(text) { match ->
        val number = match.groupValues[1]
        val spokenNumber = decimalToWords(number)
        "$spokenNumber بالمئة"
    }

    private fun replaceAbbreviationsAndSymbols(text: String): String {
        var result = text
        abbreviations.forEach { (pattern, replacement) -> result = pattern.replace(result, replacement) }
        result = Regex("(?i)(?<![\\p{L}])PDF(?![\\p{L}])").replace(result, "بي دي إف")
            .replace(Regex("(?i)(?<![\\p{L}])km(?![\\p{L}])"), "كيلومتر")
            .replace(Regex("(?i)(?<![\\p{L}])kg(?![\\p{L}])"), "كيلوغرام")
        result = result.replace(Regex("(?<=\\d)\\s*\\+\\s*(?=\\d)"), " زائد ")
            .replace(Regex("(?<=\\d)\\s*[-−]\\s*(?=\\d)"), " ناقص ")
            .replace(Regex("(?<=\\d)\\s*[×*]\\s*(?=\\d)"), " ضرب ")
            .replace(Regex("(?<=\\d)\\s*[÷/]\\s*(?=\\d)"), " على ")
            .replace(Regex("(?<=\\d)\\s*=\\s*(?=\\d)"), " يساوي ")
            .replace("&", " و ")
            .replace("@", " عند ")
        return result
    }

    private fun replaceNegativeNumbers(text: String): String = negativeNumberPattern.replace(text) { match ->
        "سالب ${decimalToWords(match.groupValues[1])}"
    }

    private fun replaceRemainingNumbers(text: String): String = decimalOrIntegerPattern.replace(text) { match ->
        decimalToWords(match.value)
    }

    private fun decimalToWords(value: String): String {
        val parts = value.split('.', limit = 2)
        val whole = parts[0].toLongOrNull() ?: return value
        if (whole > MAX_SUPPORTED_NUMBER) return value
        if (parts.size == 1) return numberToWords(whole.toInt())
        val fraction = parts[1]
        val fractionValue = fraction.toLongOrNull() ?: return value
        if (fractionValue > MAX_SUPPORTED_NUMBER) return value
        return "${numberToWords(whole.toInt())} فاصلة ${numberToWords(fractionValue.toInt())}"
    }

    /** Spell fractional decimal digits individually, retaining leading zeroes. */
    private fun digitsToWords(digits: String): String = digits.map { numberToWords(it.digitToInt()) }.joinToString(" ")

    private fun spellDigitSequence(value: String): String {
        val groups = Regex("[0-9]+").findAll(value).map { group ->
            group.value.map { numberToWords(it.digitToInt()) }.joinToString(" ")
        }.toList()
        val spoken = groups.joinToString("، ")
        return if (value.startsWith('+')) "زائد $spoken" else spoken
    }

    private fun ordinalDay(day: Int): String = when (day) {
        1 -> "الأول"
        2 -> "الثاني"
        3 -> "الثالث"
        4 -> "الرابع"
        5 -> "الخامس"
        6 -> "السادس"
        7 -> "السابع"
        8 -> "الثامن"
        9 -> "التاسع"
        10 -> "العاشر"
        11 -> "الحادي عشر"
        12 -> "الثاني عشر"
        13 -> "الثالث عشر"
        14 -> "الرابع عشر"
        15 -> "الخامس عشر"
        16 -> "السادس عشر"
        17 -> "السابع عشر"
        18 -> "الثامن عشر"
        19 -> "التاسع عشر"
        20 -> "العشرون"
        21 -> "الحادي والعشرون"
        22 -> "الثاني والعشرون"
        23 -> "الثالث والعشرون"
        24 -> "الرابع والعشرون"
        25 -> "الخامس والعشرون"
        26 -> "السادس والعشرون"
        27 -> "السابع والعشرون"
        28 -> "الثامن والعشرون"
        29 -> "التاسع والعشرون"
        30 -> "الثلاثون"
        31 -> "الحادي والثلاثون"
        else -> numberToWords(day)
    }

    /** Cardinal numbers from 0 through 999,999; forms are intentionally context-neutral. */
    private fun numberToWords(number: Int): String = numberToWords(number.toLong())

    private fun numberToWords(number: Long): String {
        if (number !in 0..MAX_SUPPORTED_NUMBER) return number.toString()
        if (number < 1000) return underThousand(number.toInt())

        val thousands = (number / 1000).toInt()
        val remainder = (number % 1000).toInt()
        val thousandPart = when (thousands) {
            1 -> "ألف"
            2 -> "ألفان"
            in 3..10 -> "${underThousand(thousands)} آلاف"
            else -> "${underThousand(thousands)} ألف"
        }
        return if (remainder == 0) thousandPart else "$thousandPart و${underThousand(remainder)}"
    }

    private fun underThousand(number: Int): String {
        if (number < 100) return underHundred(number)
        val hundreds = number / 100
        val remainder = number % 100
        val hundredWord = when (hundreds) {
            1 -> "مئة"
            2 -> "مئتان"
            3 -> "ثلاثمئة"
            4 -> "أربعمئة"
            5 -> "خمسمئة"
            6 -> "ستمئة"
            7 -> "سبعمئة"
            8 -> "ثمانمئة"
            else -> "تسعمئة"
        }
        return if (remainder == 0) hundredWord else "$hundredWord و${underHundred(remainder)}"
    }

    private fun underHundred(number: Int): String {
        val units = arrayOf("صفر", "واحد", "اثنان", "ثلاثة", "أربعة", "خمسة", "ستة", "سبعة", "ثمانية", "تسعة")
        val teens = arrayOf("عشرة", "أحد عشر", "اثنا عشر", "ثلاثة عشر", "أربعة عشر", "خمسة عشر", "ستة عشر", "سبعة عشر", "ثمانية عشر", "تسعة عشر")
        val tens = arrayOf("", "", "عشرون", "ثلاثون", "أربعون", "خمسون", "ستون", "سبعون", "ثمانون", "تسعون")
        if (number < 10) return units[number]
        if (number < 20) return teens[number - 10]
        val unit = number % 10
        val ten = tens[number / 10]
        return if (unit == 0) ten else "${units[unit]} و$ten"
    }

    private fun placeholder(index: Int): String {
        var remaining = index
        val encoded = StringBuilder()
        do {
            encoded.append(('a'.code + remaining % 26).toChar())
            remaining /= 26
        } while (remaining > 0)
        return "\uE000$encoded\uE001"
    }

    private const val MAX_SUPPORTED_NUMBER = 999_999L
}
