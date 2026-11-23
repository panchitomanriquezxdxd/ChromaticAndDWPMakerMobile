package com.bichoelproxd.chromaticscalegenerator

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/** Carga la plantilla de programa de DirectWave (assets/dwp_template.json, la misma del HTML). */
object DwpTemplateLoader {

    private const val ASSET = "dwp_template.json"

    @Volatile
    private var cached: DwpTemplate? = null

    fun load(context: Context): DwpTemplate {
        cached?.let { return it }
        val json = JSONObject(context.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) })
        fun b64(s: String) = Base64.decode(s, Base64.DEFAULT)
        val regionArray = json.getJSONArray("region")
        val region = List(regionArray.length()) { i ->
            val pair = regionArray.getJSONArray(i)
            pair.getInt(0) to b64(pair.getString(1))
        }
        val tpl = DwpTemplate(
            prefix = b64(json.getString("prefix")),
            hdr = b64(json.getString("hdr")),
            rest = b64(json.getString("rest")),
            region = region,
            d503 = b64(json.getString("d503")),
            d500 = b64(json.getString("d500")),
        )
        cached = tpl
        return tpl
    }
}
