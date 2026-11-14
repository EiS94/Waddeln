package com.schaubeck.waddeln.web

import com.schaubeck.waddeln.platform.Sharing

/** Clipboard and the system share sheet (where the browser has one, e.g. on phones). */
object WebSharing : Sharing {
    override fun copy(label: String, text: String, confirmation: String) {
        copyText(text, confirmation)
    }

    override fun share(text: String, chooserTitle: String) {
        if (canShare()) shareText(text) else copyText(text, "")
    }
}

/** Copies [text]; [confirmation] is shown briefly at the bottom (browsers do not confirm by themselves). */
private fun copyText(text: String, confirmation: String): Unit = js(
    """
    {
      const toast = (message) => {
        if (!message) return;
        const el = document.createElement('div');
        el.textContent = message;
        el.style.cssText = 'position:fixed;left:50%;bottom:48px;transform:translateX(-50%);padding:10px 18px;' +
          'border-radius:20px;background:rgba(30,20,10,0.92);color:#fff;font:15px sans-serif;z-index:10;pointer-events:none';
        document.body.appendChild(el);
        setTimeout(() => el.remove(), 2000);
      };
      if (navigator.clipboard) navigator.clipboard.writeText(text).then(() => toast(confirmation), () => {});
    }
    """,
)

private fun canShare(): Boolean = js("typeof navigator.share === 'function'")

private fun shareText(text: String): Unit = js("{ navigator.share({ text: text }).catch(() => {}); }")
