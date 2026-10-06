package com.example.offlinetransitmap

internal enum class UpdateDecision { APPLY, WAIT, EXPIRED, OLDER }
internal fun busUpdateDecision(start: Int, end: Int, currentEnd: Int, today: Int): UpdateDecision {
    require(start <= end)
    return when {
        end < today -> UpdateDecision.EXPIRED
        end < currentEnd -> UpdateDecision.OLDER
        start > today -> UpdateDecision.WAIT
        else -> UpdateDecision.APPLY
    }
}
