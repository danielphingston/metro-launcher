package io.github.danielphingston.metro.ui.search

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.widget.Toast
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AssistantIntents

/** The assistant app the search row sends to; [name] is the label to show ("Claude"). */
internal data class AssistantTarget(val app: AppInfo, val name: String)

/** Sends a typed question to an assistant app with plain Android intents; no APIs, no accounts. */
internal object AssistantAsk {
    /**
     * Shares [query] with [packageName]. When the app doesn't take shared text, the question goes
     * to the clipboard, the app opens and a toast says so. False when nothing could be opened.
     */
    fun ask(context: Context, packageName: String, query: String, bounds: Rect?, options: Bundle?): Boolean {
        val pm = context.packageManager
        val send = AssistantIntents.send(packageName, query)
        val launch = pm.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val plan = AssistantIntents.plan(canSend = pm.resolveActivity(send, 0) != null, canLaunch = launch != null)
        if (plan == AssistantIntents.Plan.SEND) {
            send.sourceBounds = bounds
            if (runCatching { context.startActivity(send, options) }.isSuccess) return true
        }
        if (launch == null) {
            Toast.makeText(context, "assistant app not found", Toast.LENGTH_SHORT).show()
            return false
        }
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("question", query))
        Toast.makeText(context, "question copied", Toast.LENGTH_SHORT).show()
        launch.sourceBounds = bounds
        return runCatching { context.startActivity(launch, options) }.isSuccess
    }
}
