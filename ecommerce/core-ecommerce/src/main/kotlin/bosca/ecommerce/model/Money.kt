package bosca.ecommerce.model

import bosca.db.annotation.DbMapper
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.Serializable

/**
 * A monetary amount: a [BigDecimal] fixed at [SCALE] decimal places using [ROUNDING].
 *
 * Money is the only monetary representation in the module — never raw [BigDecimal] or [Double] for
 * prices, balances, or taxes. It carries three boundary representations:
 *  - **jsonb / GraphQL / config blobs** — [MoneySerializer] encodes it as a plain decimal string
 *    (e.g. `"19.9900"`), so it round-trips losslessly through `JsonElement`.
 *  - **`numeric` columns** — [MoneyMapper] (via [DbMapper]) binds/reads it as a [BigDecimal]. The
 *    `@DbMapper` takes precedence over the `@Serializable` auto-jsonb path in the repository
 *    generator, so a `Money` field on a `numeric` column binds correctly.
 *
 * Construction always normalizes to [SCALE]/[ROUNDING], so two `Money` values that are numerically
 * equal are also `equals` (same scale).
 */
@Serializable(with = MoneySerializer::class)
@DbMapper(MoneyMapper::class)
class Money(amount: BigDecimal) : Comparable<Money> {

    /** The normalized amount: always scale [SCALE], rounded [ROUNDING]. */
    val amount: BigDecimal = amount.setScale(SCALE, ROUNDING)

    operator fun plus(other: Money): Money = Money(amount.add(other.amount))
    operator fun minus(other: Money): Money = Money(amount.subtract(other.amount))
    operator fun times(quantity: Int): Money = Money(amount.multiply(BigDecimal(quantity)))
    operator fun times(factor: BigDecimal): Money = Money(amount.multiply(factor))
    operator fun div(divisor: Int): Money = Money(amount.divide(BigDecimal(divisor), SCALE, ROUNDING))
    operator fun div(divisor: BigDecimal): Money = Money(amount.divide(divisor, SCALE, ROUNDING))
    operator fun unaryMinus(): Money = Money(amount.negate())

    fun abs(): Money = Money(amount.abs())

    /** This amount scaled by a percentage (e.g. `percentage(BigDecimal("10"))` = 10% of this). */
    fun percentage(percent: BigDecimal): Money = Money(amount.multiply(percent).divide(HUNDRED, SCALE, ROUNDING))

    /**
     * This amount rounded to [scale] decimal places (a currency's settlement precision) under [ROUNDING].
     * The result is still stored at [SCALE], but with no residual below [scale] — so a quantized
     * money-of-record matches what a gateway can actually move (e.g. `quantize(2)` of `10.825` = `10.82`).
     */
    fun quantize(scale: Int): Money = Money(amount.setScale(scale, ROUNDING))

    val isZero: Boolean get() = amount.signum() == 0
    val isPositive: Boolean get() = amount.signum() > 0
    val isNegative: Boolean get() = amount.signum() < 0

    /** This amount raised to a floor of [min] (the larger of the two). */
    fun coerceAtLeast(min: Money): Money = if (this < min) min else this

    /** This amount capped at a ceiling of [max] (the smaller of the two). */
    fun coerceAtMost(max: Money): Money = if (this > max) max else this

    override fun compareTo(other: Money): Int = amount.compareTo(other.amount)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Money) return false
        return amount == other.amount
    }

    override fun hashCode(): Int = amount.hashCode()

    /** The plain decimal string with [SCALE] places, e.g. `"19.9900"`. */
    override fun toString(): String = amount.toPlainString()

    companion object {
        const val SCALE: Int = 4
        val ROUNDING: RoundingMode = RoundingMode.HALF_EVEN
        private val HUNDRED = BigDecimal(100)

        val ZERO: Money = Money(BigDecimal.ZERO)

        fun of(value: String): Money = Money(BigDecimal(value))
        fun of(value: Int): Money = Money(BigDecimal(value))
        fun of(value: Long): Money = Money(BigDecimal(value))
        fun of(value: Double): Money = Money(BigDecimal.valueOf(value))

        /** Sum a collection of amounts, returning [ZERO] when empty. */
        fun sum(values: Iterable<Money>): Money = values.fold(ZERO) { acc, m -> acc + m }
    }
}

fun String.toMoney(): Money = Money.of(this)
fun Int.toMoney(): Money = Money.of(this)
fun BigDecimal.toMoney(): Money = Money(this)
