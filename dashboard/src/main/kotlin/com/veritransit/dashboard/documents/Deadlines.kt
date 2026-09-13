package com.veritransit.dashboard.documents

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month

/**
 * The clocks a consignment's paperwork starts (#27, the deadline table).
 *
 * Each deadline is the arithmetic of one provision and nothing more — no
 * business calendar, no holiday shifting, no grace the rule does not state —
 * so a date on screen can be checked against the rule with a pen.
 */
sealed interface Deadline {
    /** What starts the clock and how long it runs, in words. */
    val label: String

    /**
     * r.138(10): one day for the first [kmPerDay] km, and one more day for
     * every further [kmPerDay] km *or part thereof*. 200 km for ordinary
     * cargo, 20 km for over-dimensional cargo.
     *
     * Explanation 1 counts a day as the period expiring at midnight of the day
     * after the bill was generated — calendar days, not 24-hour blocks — so a
     * bill generated late in the evening still gets the whole next day.
     */
    data class EwayBillValidity(val kmPerDay: Int = ROAD_KM_PER_DAY) : Deadline {
        override val label: String =
            "1 day up to $kmPerDay km, +1 day per further $kmPerDay km or part thereof"

        fun days(distanceKm: Int): Int {
            require(distanceKm >= 0) { "distance cannot be negative: $distanceKm" }
            return maxOf(1, (distanceKm + kmPerDay - 1) / kmPerDay)
        }

        /** The last calendar day the bill is valid on. */
        fun lastValidDay(generatedAt: LocalDateTime, distanceKm: Int): LocalDate =
            generatedAt.toLocalDate().plusDays(days(distanceKm).toLong())

        /** The moment validity runs out: the midnight that closes [lastValidDay]. */
        fun expiresAt(generatedAt: LocalDateTime, distanceKm: Int): LocalDateTime =
            lastValidDay(generatedAt, distanceKm).plusDays(1).atStartOfDay()
    }

    data class HoursAfter(val hours: Long, val anchor: String) : Deadline {
        override val label: String = "within $hours hours after $anchor"
        fun due(from: LocalDateTime): LocalDateTime = from.plusHours(hours)
    }

    data class DaysAfter(val days: Long, val anchor: String) : Deadline {
        override val label: String = "within $days days of $anchor"
        fun due(from: LocalDate): LocalDate = from.plusDays(days)
    }

    data class MonthsAfter(val months: Long, val anchor: String) : Deadline {
        override val label: String = "for $months months from $anchor"
        fun due(from: LocalDate): LocalDate = from.plusMonths(months)
    }

    data class YearsAfter(val years: Long, val anchor: String) : Deadline {
        override val label: String = "within $years ${if (years == 1L) "year" else "years"} of $anchor"
        fun due(from: LocalDate): LocalDate = from.plusYears(years)
    }

    /**
     * s.34(2): a credit note against a supply is declared by 30 November
     * following the end of the financial year of that supply, or by the date
     * the annual return is furnished — whichever is earlier.
     */
    data object CreditNoteCutoff : Deadline {
        override val label: String =
            "30 November after the financial year of supply, or the annual return date if earlier"

        fun due(supplyDate: LocalDate, annualReturnFiledOn: LocalDate? = null): LocalDate {
            val cutoff = LocalDate.of(financialYearEnd(supplyDate).year, Month.NOVEMBER, 30)
            return if (annualReturnFiledOn != null && annualReturnFiledOn < cutoff) annualReturnFiledOn else cutoff
        }
    }

    /**
     * r.45(3) and its Explanation: a job-work challan is reported in ITC-04
     * for its "specified period" — April–September / October–March when the
     * principal's aggregate turnover in the preceding financial year exceeded
     * ₹5 crore, the whole financial year otherwise — by the 25th day of the
     * month after that period.
     */
    data object Itc04 : Deadline {
        override val label: String =
            "25th of the month after the period (half-year above ₹5 crore turnover, else financial year)"

        fun periodEnd(challanDate: LocalDate, turnoverAboveFiveCrore: Boolean): LocalDate =
            if (turnoverAboveFiveCrore && challanDate.monthValue in 4..9) {
                LocalDate.of(challanDate.year, Month.SEPTEMBER, 30)
            } else {
                financialYearEnd(challanDate)
            }

        fun due(challanDate: LocalDate, turnoverAboveFiveCrore: Boolean): LocalDate =
            periodEnd(challanDate, turnoverAboveFiveCrore).plusMonths(1).withDayOfMonth(25)
    }

    companion object {
        const val ROAD_KM_PER_DAY = 200
        const val OVER_DIMENSIONAL_KM_PER_DAY = 20

        /** The Indian financial year runs April to March. */
        fun financialYearEnd(date: LocalDate): LocalDate =
            LocalDate.of(if (date.monthValue >= 4) date.year + 1 else date.year, Month.MARCH, 31)
    }
}
