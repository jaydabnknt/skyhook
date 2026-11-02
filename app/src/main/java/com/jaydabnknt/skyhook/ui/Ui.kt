package com.jaydabnknt.skyhook.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jaydabnknt.skyhook.Prefs
import com.jaydabnknt.skyhook.cloud.Destination
import com.jaydabnknt.skyhook.cloud.safeFileName

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** Pads a root view for status/navigation bars (targetSdk 35+ is always edge-to-edge). */
fun View.applySystemBarPadding() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        v.setPadding(b.left, b.top, b.right, b.bottom)
        insets
    }
}

object DownloadDialog {
    /**
     * Asks for file name + destination. Cloud destinations that aren't connected
     * are shown but send the user to Settings.
     */
    fun show(
        activity: Activity,
        suggestedName: String,
        info: String?,
        onCancel: () -> Unit = {},
        onConfirm: (name: String, dest: Destination) -> Unit,
    ) {
        val ctx = activity
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(22), ctx.dp(8), ctx.dp(22), 0)
        }
        if (!info.isNullOrBlank()) box.addView(TextView(ctx).apply { text = info; alpha = 0.7f; setPadding(0, 0, 0, ctx.dp(8)) })
        val name = EditText(ctx).apply { setText(safeFileName(suggestedName)); setSingleLine() }
        box.addView(name)
        box.addView(TextView(ctx).apply { text = "Save to"; setPadding(0, ctx.dp(14), 0, ctx.dp(2)); alpha = 0.7f })
        val group = RadioGroup(ctx)
        val preferred = Prefs.destination.takeIf { it.isConnected() } ?: Destination.LOCAL
        Destination.entries.forEach { d ->
            group.addView(RadioButton(ctx).apply {
                id = View.generateViewId()
                tag = d
                text = if (d.isConnected()) d.label else "${d.label}  (not connected)"
                isChecked = d == preferred
                alpha = if (d.isConnected()) 1f else 0.5f
            })
        }
        box.addView(group)
        if (preferred.isCloud) box.addView(TextView(ctx).apply {
            text = "Streams straight to the cloud — nothing is saved on the phone."
            textSize = 12f; alpha = 0.6f; setPadding(0, ctx.dp(4), 0, 0)
        })
        group.setOnCheckedChangeListener { g, checkedId ->
            val d = g.findViewById<RadioButton>(checkedId)?.tag as? Destination ?: return@setOnCheckedChangeListener
            if (!d.isConnected()) {
                Toast.makeText(ctx, "Connect ${d.label} in Settings first", Toast.LENGTH_SHORT).show()
                ctx.startActivity(Intent(ctx, SettingsActivity::class.java))
            }
        }
        var confirmed = false
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Download")
            .setView(box)
            .setPositiveButton("Download") { _, _ ->
                val d = group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? Destination ?: Destination.LOCAL
                if (!d.isConnected()) {
                    Toast.makeText(ctx, "${d.label} isn't connected", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                confirmed = true
                onConfirm(name.text.toString().ifBlank { suggestedName }, d)
                Toast.makeText(ctx, "Downloading to ${d.label}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (!confirmed) onCancel() }
            .show()
    }
}
