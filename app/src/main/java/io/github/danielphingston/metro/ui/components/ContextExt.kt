package io.github.danielphingston.metro.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** The [Activity] behind this context, however many wrappers (themes, Compose) sit in between; null without one. */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
