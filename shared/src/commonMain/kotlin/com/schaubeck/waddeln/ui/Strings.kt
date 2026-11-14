package com.schaubeck.waddeln.ui

import com.schaubeck.waddeln.resources.Res
import com.schaubeck.waddeln.resources.allStringArrayResources
import com.schaubeck.waddeln.resources.allStringResources
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray

/**
 * All texts of the app, read once at start. Compose resources are read asynchronously, but view
 * models build speech bubbles and messages right away; they take their texts from here.
 */
class Strings(private val texts: Map<String, String>, private val arrays: Map<String, List<String>>) {

    /** The text [id] with its placeholders (%1$s, %2$d, ...) filled in from [args]. */
    fun get(id: StringResource, vararg args: Any): String = format(texts.getValue(id.key), args)

    fun array(id: StringArrayResource): List<String> = arrays.getValue(id.key)

    companion object {
        suspend fun load(): Strings = Strings(
            texts = Res.allStringResources.values.associate { it.key to getString(it) },
            arrays = Res.allStringArrayResources.values.associate { it.key to getStringArray(it) },
        )

        private val PLACEHOLDER = Regex("""%(\d+)\$[ds]""")

        /** Fills in positional placeholders the way Compose resources and Android do for text and numbers. */
        fun format(template: String, args: Array<out Any>): String =
            if (args.isEmpty()) template else PLACEHOLDER.replace(template) { match ->
                args.getOrNull(match.groupValues[1].toInt() - 1)?.toString() ?: match.value
            }
    }
}
