package dev.harness.core

import android.content.res.AssetManager
import java.io.File
import java.io.FileNotFoundException

/** Where the shipped default prompt files come from. Paths are relative ("tools/bash.md"). */
interface DefaultsSource {
    fun list(): List<String>
    fun read(path: String): String?
}

/** Reads defaults from the APK: assets/defaults/prompts/. */
class AssetDefaultsSource(
    private val assets: AssetManager,
    private val root: String = "defaults/prompts",
) : DefaultsSource {

    override fun list(): List<String> {
        val out = mutableListOf<String>()
        fun walk(dir: String, rel: String) {
            val children = assets.list(dir) ?: return
            for (child in children) {
                val full = "$dir/$child"
                val relPath = if (rel.isEmpty()) child else "$rel/$child"
                val grand = assets.list(full)
                if (grand != null && grand.isNotEmpty()) walk(full, relPath)
                else if (child.endsWith(".md")) out += relPath
            }
        }
        walk(root, "")
        return out.sorted()
    }

    override fun read(path: String): String? = try {
        assets.open("$root/$path").bufferedReader().use { it.readText() }
    } catch (_: FileNotFoundException) {
        null
    } catch (_: java.io.IOException) {
        null
    }
}

/** Reads defaults from a directory on disk. Used by tests and by "load prompt set" flows. */
class DirectoryDefaultsSource(private val dir: File) : DefaultsSource {
    override fun list(): List<String> =
        dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".md") }
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .sorted()
            .toList()

    override fun read(path: String): String? = File(dir, path).takeIf { it.isFile }?.readText()
}
