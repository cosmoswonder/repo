package recloudstream

import android.app.AlertDialog
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OlehdtvPlugin : Plugin() {

    companion object {
        const val PREFS = "olehdtv"
        const val LANG_KEY = "tmdb_lang"
    }

    override fun load(context: Context) {
        registerMainAPI(OlehdtvProvider())

        openSettings = { ctx ->
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val current = prefs.getString(LANG_KEY, null)
                ?: if (java.util.Locale.getDefault().language == "zh") "zh-CN" else "en-US"
            val currentIdx = if (current == "en-US") 1 else 0

            AlertDialog.Builder(ctx)
                .setTitle("TMDB 语言 / Language")
                .setSingleChoiceItems(
                    arrayOf("中文 (zh-CN)", "English (en-US)"),
                    currentIdx,
                ) { dialog, which ->
                    prefs.edit()
                        .putString(LANG_KEY, if (which == 0) "zh-CN" else "en-US")
                        .apply()
                    dialog.dismiss()
                }
                .setNegativeButton("取消 / Cancel") { d, _ -> d.dismiss() }
                .show()
        }
    }
}
