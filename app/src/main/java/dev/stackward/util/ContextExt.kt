package dev.stackward.util

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity

fun Context.findFragmentActivity(): FragmentActivity {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    throw IllegalStateException("FragmentActivity not found in context chain: $this")
}
