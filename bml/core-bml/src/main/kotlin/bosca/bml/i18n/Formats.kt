package bosca.bml.i18n

import bosca.bml.render.currentLocale
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency

/**
 * Locale-aware formatting helpers, ambient in every generated unit like
 * [t]. Thin wrappers over the JDK's CLDR-backed formatters bound to the ambient render locale —
 * no ICU4J (native-image constraint). NATIVE NOTE: GraalVM images bundle only the default
 * locale's data unless built with `-H:IncludeLocales=…`; without it these degrade silently to
 * root-locale output (see the bml-message-server build file and the site-deployment docs).
 *
 * Timezone is always an explicit parameter — the render has no ambient timezone in v1
 * (emails carry `BmlMessageContext.timezone`; sites pass their own).
 */

/** `1234567.89` -> `1,234,567.89` (en) / `1.234.567,89` (es), per the ambient locale. */
public suspend fun formatNumber(value: Number): String =
    NumberFormat.getNumberInstance(currentLocale()).format(value)

/** Formats a FRACTION: `formatPercent(0.42)` -> `42%`, per the ambient locale. */
public suspend fun formatPercent(fraction: Number): String =
    NumberFormat.getPercentInstance(currentLocale()).format(fraction)

/**
 * `formatCurrency(1234.5, "USD")` -> `$1,234.50` (en-US) / `1.234,50 $` (de), per the ambient
 * locale. Fraction digits follow the CURRENCY (JPY renders whole), not the locale's default —
 * set explicitly because `setCurrency` alone does not adjust them.
 */
public suspend fun formatCurrency(amount: Number, currencyCode: String): String {
    val currency = Currency.getInstance(currencyCode)
    val format = NumberFormat.getCurrencyInstance(currentLocale()).apply {
        this.currency = currency
        minimumFractionDigits = currency.defaultFractionDigits
        maximumFractionDigits = currency.defaultFractionDigits
    }
    return format.format(amount)
}

/** A calendar date in the ambient locale: `formatDate(LocalDate.of(2026, 7, 4))` -> `Jul 4, 2026` (en). */
public suspend fun formatDate(date: LocalDate, style: FormatStyle = FormatStyle.MEDIUM): String =
    date.format(DateTimeFormatter.ofLocalizedDate(style).withLocale(currentLocale()))

/** An instant as a date in [zone], in the ambient locale. */
public suspend fun formatDate(instant: Instant, zone: ZoneId, style: FormatStyle = FormatStyle.MEDIUM): String =
    DateTimeFormatter.ofLocalizedDate(style).withLocale(currentLocale()).withZone(zone).format(instant)

/** An instant as date + time in [zone], in the ambient locale. */
public suspend fun formatDateTime(
    instant: Instant,
    zone: ZoneId,
    dateStyle: FormatStyle = FormatStyle.MEDIUM,
    timeStyle: FormatStyle = FormatStyle.SHORT,
): String = DateTimeFormatter.ofLocalizedDateTime(dateStyle, timeStyle)
    .withLocale(currentLocale())
    .withZone(zone)
    .format(instant)
