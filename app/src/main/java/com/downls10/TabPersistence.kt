package com.downls10

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedTab(
    val isHome: Boolean,
    val url: String,
    val title: String,
    val backStack: List<String> = emptyList(),
    val forwardStack: List<String> = emptyList(),
    /** الحالة الكاملة لـ WebView (بما فيها سجل الرجوع/التقدم) بترميز Base64 */
    val webState: String? = null
)

/** يحفظ حالة التبويبات المفتوحة (روابطها + تاريخ التصفح الكامل لكل تبويب) حتى تبقى بعد إغلاق التطبيق */
object TabPersistence {
    private const val PREFS = "tabs_prefs"
    private const val KEY_TABS = "saved_tabs"
    private const val KEY_CURRENT = "current_index"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, tabs: List<SavedTab>, currentIndex: Int) {
        val arr = JSONArray()
        tabs.forEach { tab ->
            arr.put(JSONObject().apply {
                put("isHome", tab.isHome)
                put("url", tab.url)
                put("title", tab.title)
                put("backStack", JSONArray(tab.backStack))
                put("forwardStack", JSONArray(tab.forwardStack))
                if (!tab.webState.isNullOrBlank()) put("webState", tab.webState)
            })
        }
        prefs(context).edit()
            .putString(KEY_TABS, arr.toString())
            .putInt(KEY_CURRENT, currentIndex)
            .apply()
    }

    fun load(context: Context): Pair<List<SavedTab>, Int> {
        val raw = prefs(context).getString(KEY_TABS, null) ?: return Pair(emptyList(), 0)
        val arr = JSONArray(raw)
        val list = mutableListOf<SavedTab>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val backArr = obj.optJSONArray("backStack")
            val fwdArr = obj.optJSONArray("forwardStack")
            val back = mutableListOf<String>()
            val fwd = mutableListOf<String>()
            if (backArr != null) for (j in 0 until backArr.length()) back.add(backArr.getString(j))
            if (fwdArr != null) for (j in 0 until fwdArr.length()) fwd.add(fwdArr.getString(j))
            list.add(
                SavedTab(
                    obj.optBoolean("isHome", true),
                    obj.optString("url"),
                    obj.optString("title"),
                    back,
                    fwd,
                    obj.optString("webState", null)
                )
            )
        }
        val currentIndex = prefs(context).getInt(KEY_CURRENT, 0)
        return Pair(list, currentIndex)
    }
}
