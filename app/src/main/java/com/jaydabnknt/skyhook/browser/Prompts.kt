package com.jaydabnknt.skyhook.browser

import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse

/**
 * Web page dialogs: alert/confirm/prompt, <select> pickers, HTTP auth, file uploads.
 * Without these, <select> menus and confirm() silently do nothing in GeckoView.
 */
class Prompts(
    private val activity: AppCompatActivity,
    private val pickFiles: (multiple: Boolean, mimes: Array<String>, done: (List<Uri>) -> Unit) -> Unit,
) : PromptDelegate {

    private fun dialog() = MaterialAlertDialogBuilder(activity)

    override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt): GeckoResult<PromptResponse> {
        val r = GeckoResult<PromptResponse>()
        dialog().setTitle(prompt.title).setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { r.complete(prompt.dismiss()) }
            .show()
        return r
    }

    override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt): GeckoResult<PromptResponse> {
        val r = GeckoResult<PromptResponse>()
        var done = false
        dialog().setTitle(prompt.title).setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                done = true; r.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                done = true; r.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE))
            }
            .setOnDismissListener { if (!done) r.complete(prompt.dismiss()) }
            .show()
        return r
    }

    override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt): GeckoResult<PromptResponse> {
        val r = GeckoResult<PromptResponse>()
        var done = false
        val input = EditText(activity).apply { setText(prompt.defaultValue ?: "") }
        dialog().setTitle(prompt.title).setMessage(prompt.message).setView(pad(input))
            .setPositiveButton(android.R.string.ok) { _, _ -> done = true; r.complete(prompt.confirm(input.text.toString())) }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (!done) r.complete(prompt.dismiss()) }
            .show()
        return r
    }

    override fun onAuthPrompt(session: GeckoSession, prompt: PromptDelegate.AuthPrompt): GeckoResult<PromptResponse> {
        val r = GeckoResult<PromptResponse>()
        var done = false
        val user = EditText(activity).apply { hint = "Username" }
        val pass = EditText(activity).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; addView(user); addView(pass) }
        dialog().setTitle(prompt.title ?: "Sign in").setMessage(prompt.message).setView(pad(box))
            .setPositiveButton("Sign in") { _, _ ->
                done = true; r.complete(prompt.confirm(user.text.toString(), pass.text.toString()))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (!done) r.complete(prompt.dismiss()) }
            .show()
        return r
    }

    override fun onChoicePrompt(session: GeckoSession, prompt: PromptDelegate.ChoicePrompt): GeckoResult<PromptResponse> {
        val r = GeckoResult<PromptResponse>()
        var done = false
        // Flatten <optgroup>s.
        val flat = mutableListOf<PromptDelegate.ChoicePrompt.Choice>()
        fun walk(cs: Array<PromptDelegate.ChoicePrompt.Choice>, depth: Int) {
            for (c in cs) {
                if (c.separator) continue
                if (c.items != null) walk(c.items!!, depth + 1) else flat += c
            }
        }
        walk(prompt.choices, 0)
        val labels = flat.map { it.label }.toTypedArray()

        val b = dialog().setTitle(prompt.title ?: prompt.message)
        if (prompt.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
            val checked = flat.map { it.selected }.toBooleanArray()
            b.setMultiChoiceItems(labels, checked) { _, i, v -> checked[i] = v }
            b.setPositiveButton(android.R.string.ok) { _, _ ->
                done = true
                r.complete(prompt.confirm(flat.filterIndexed { i, _ -> checked[i] }.toTypedArray()))
            }
        } else {
            val sel = flat.indexOfFirst { it.selected }
            b.setSingleChoiceItems(labels, sel) { d, i ->
                if (!flat[i].disabled) {
                    done = true
                    r.complete(prompt.confirm(flat[i]))
                    d.dismiss()
                }
            }
        }
        b.setOnDismissListener { if (!done) r.complete(prompt.dismiss()) }.show()
        return r
    }

    override fun onPopupPrompt(session: GeckoSession, prompt: PromptDelegate.PopupPrompt): GeckoResult<PromptResponse> =
        GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))

    override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: PromptDelegate.BeforeUnloadPrompt): GeckoResult<PromptResponse> =
        GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))

    override fun onFilePrompt(session: GeckoSession, prompt: PromptDelegate.FilePrompt): GeckoResult<PromptResponse> {
        val r = GeckoResult<PromptResponse>()
        val multiple = prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE
        val mimes = prompt.mimeTypes?.filter { it.contains('/') }?.toTypedArray()?.takeIf { it.isNotEmpty() } ?: arrayOf("*/*")
        pickFiles(multiple, mimes) { uris ->
            r.complete(
                when {
                    uris.isEmpty() -> prompt.dismiss()
                    multiple -> prompt.confirm(activity, uris.toTypedArray())
                    else -> prompt.confirm(activity, uris.first())
                }
            )
        }
        return r
    }

    private fun pad(v: android.view.View) = LinearLayout(activity).apply {
        val p = (20 * resources.displayMetrics.density).toInt()
        setPadding(p, p / 2, p, 0)
        addView(v, LinearLayout.LayoutParams(-1, -2))
    }
}

/** Helper so the activity can register one launcher and reuse it for file prompts. */
class FilePicker(activity: AppCompatActivity) {
    private var pending: ((List<Uri>) -> Unit)? = null
    private val single: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            pending?.invoke(listOfNotNull(uri)); pending = null
        }
    private val multi: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            pending?.invoke(uris); pending = null
        }

    fun pick(multiple: Boolean, mimes: Array<String>, done: (List<Uri>) -> Unit) {
        pending?.invoke(emptyList())
        pending = done
        if (multiple) multi.launch(mimes) else single.launch(mimes)
    }
}
