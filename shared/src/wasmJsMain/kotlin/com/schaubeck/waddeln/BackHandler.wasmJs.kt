package com.schaubeck.waddeln

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * The browser's back button goes back to the menu instead of leaving the page: while [enabled], an
 * extra history entry is pushed; going back pops it and calls [onBack].
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val current by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        var popped = false
        pushHistoryEntry()
        val listener = addPopListener {
            popped = true
            current()
        }
        onDispose {
            removePopListener(listener)
            // Left by our own button (e.g. "Zurück"): take the extra entry away again.
            if (!popped) dropHistoryEntry()
        }
    }
}

private fun pushHistoryEntry(): Unit = js("history.pushState({ waddeln: true }, '')")

/** Goes back over our own entry without anybody reacting to it. */
private fun dropHistoryEntry(): Unit = js(
    """
    {
      if (!(history.state && history.state.waddeln)) return;
      const swallow = (e) => e.stopImmediatePropagation();
      window.addEventListener('popstate', swallow, { once: true, capture: true });
      history.back();
    }
    """,
)

private fun addPopListener(callback: () -> Unit): JsAny = js(
    "{ const l = () => callback(); window.addEventListener('popstate', l); return l; }",
)

private fun removePopListener(listener: JsAny): Unit = js("window.removeEventListener('popstate', listener)")
