package com.schaubeck.waddeln.platform

import android.content.Context
import com.schaubeck.waddeln.resources.Res
import com.schaubeck.waddeln.rules.ml.MlpQFunction
import com.schaubeck.waddeln.rules.ml.QFunction

/**
 * Loads the trained network (`composeResources/files/waddeln.mlp`, the same file the browser uses) and
 * computes it in Kotlin with [MlpQFunction]; throws if there is none. Called on the bot thread.
 */
class AssetModelLoader(private val context: Context) : ModelLoader {
    override fun load(): QFunction {
        // Compose resources are assets of the APK; read directly, as this runs off the main thread.
        val path = Res.getUri(MODEL_FILE).removePrefix(ASSET_PREFIX)
        return MlpQFunction(context.assets.open(path).use { it.readBytes() })
    }

    private companion object {
        const val MODEL_FILE = "files/waddeln.mlp"
        const val ASSET_PREFIX = "file:///android_asset/"
    }
}
