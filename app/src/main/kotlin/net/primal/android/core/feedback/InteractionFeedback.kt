package net.primal.android.core.feedback

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

fun View.performConfirmHaptic() {
    val feedbackType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.VIRTUAL_KEY
    }
    performHapticFeedback(feedbackType)
}

fun View.performSelectionHaptic() {
    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}
