package dev.harness.core.memory

import android.content.res.AssetManager

/** Default memory files shipped in assets/defaults/memory, written once on first use. */
object MemorySeed {
    const val DIR = "defaults/memory"

    fun fromAssets(assets: AssetManager): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun walk(dir: String, rel: String) {
            val names = runCatching { assets.list(dir) }.getOrNull().orEmpty()
            for (n in names) {
                val path = "$dir/$n"
                val r = if (rel.isEmpty()) n else "$rel/$n"
                val kids = runCatching { assets.list(path) }.getOrNull().orEmpty()
                if (kids.isNotEmpty()) walk(path, r)
                else if (n.endsWith(".md")) runCatching { assets.open(path).use { it.readBytes().decodeToString() } }.getOrNull()?.let { out[r] = it }
            }
        }
        walk(DIR, "")
        return out
    }
}
