package com.imslept.screenannotator

import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Ponte tra l'activity di inserimento testo e l'overlay di disegno. */
object OverlayBridge {
    var textListener: ((String?) -> Unit)? = null
}

/** Finestra trasparente che mostra un dialogo per scrivere il testo da inserire sullo schermo. */
class TextInputActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val d = resources.displayMetrics.density
        val input = EditText(this).apply {
            hint = getString(R.string.text_dialog_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * d).toInt()
            setPadding(pad, (8 * d).toInt(), pad, 0)
            addView(input)
        }

        var delivered = false
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.text_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                delivered = true
                OverlayBridge.textListener?.invoke(input.text.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                if (!delivered) OverlayBridge.textListener?.invoke(null)
                finish()
            }
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }
}
