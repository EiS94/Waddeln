// Loads the app only in browsers with WasmGC; the others get a notice instead of an empty page.
// If the app does not start (an error, or nothing after a while), a diagnosis is shown instead of a
// white page, so players can send a screenshot of it.
(function () {
    const errors = [];
    let ready = false;
    let shown = false;

    const note = (text) => {
        if (errors.length < 8) errors.push(String(text).slice(0, 400));
    };

    window.addEventListener('error', (e) => {
        note((e.message || 'Fehler') + (e.filename ? ' @ ' + e.filename.split('/').pop() + ':' + e.lineno : ''));
        if (!ready) showProblem();
    });
    window.addEventListener('unhandledrejection', (e) => {
        const r = e.reason;
        note('Promise: ' + (r && (r.stack || r.message) || r));
        if (!ready) showProblem();
    });

    // Called from the app.
    window.waddelnStarted = () => {
        ready = true;
        const box = document.getElementById('startProblem');
        if (box) box.remove();
    };
    window.waddelnStartError = (message) => {
        note(message);
        showProblem();
    };

    function webgl2() {
        try {
            return !!document.createElement('canvas').getContext('webgl2');
        } catch (e) {
            return false;
        }
    }

    function showProblem() {
        if (shown) {
            const list = document.getElementById('startProblemErrors');
            if (list) list.textContent = errors.join('\n');
            return;
        }
        shown = true;
        const loading = document.getElementById('loading');
        if (loading) loading.remove();
        const box = document.createElement('div');
        box.id = 'startProblem';
        box.style.cssText = 'position:fixed;inset:0;z-index:20;overflow:auto;padding:24px;box-sizing:border-box;' +
            'background:#3B2A1A;color:#fff;font:15px/1.5 system-ui,sans-serif';
        const title = document.createElement('div');
        title.style.cssText = 'color:#FFD54F;font-weight:700;font-size:20px;margin-bottom:8px';
        title.textContent = 'Waddeln startet nicht richtig.';
        const text = document.createElement('div');
        text.textContent = 'Bitte mach einen Screenshot von dieser Seite und schick ihn an waddeln@schaumler.de. ' +
            'Neu laden hilft manchmal.';
        const info = document.createElement('pre');
        info.style.cssText = 'white-space:pre-wrap;word-break:break-word;font:12px/1.4 ui-monospace,monospace;' +
            'background:rgba(0,0,0,.35);padding:10px;border-radius:8px;margin-top:14px';
        info.textContent = [
            'Browser: ' + navigator.userAgent,
            'Bildschirm: ' + screen.width + '×' + screen.height + ' @' + devicePixelRatio +
                ', Fenster ' + innerWidth + '×' + innerHeight,
            'WebGL 2: ' + (webgl2() ? 'ja' : 'nein') + ', Sprache: ' + (navigator.languages || [navigator.language]).join(','),
            'Speicher: ' + (navigator.deviceMemory ? navigator.deviceMemory + ' GB' : 'unbekannt'),
        ].join('\n');
        const list = document.createElement('pre');
        list.id = 'startProblemErrors';
        list.style.cssText = info.style.cssText;
        list.textContent = errors.length ? errors.join('\n') : 'Kein Fehler gemeldet – die App hat nach 20 s noch nicht gestartet.';
        const reload = document.createElement('button');
        reload.textContent = 'Neu laden';
        reload.style.cssText = 'margin-top:16px;padding:12px 22px;border:0;border-radius:22px;background:#6D4C2F;' +
            'color:#fff;font:600 16px system-ui,sans-serif';
        reload.onclick = () => location.reload();
        box.append(title, text, info, list, reload);
        // Not into <body>: Compose attaches a shadow root there, which hides everything else in it.
        document.documentElement.appendChild(box);
    }

    // A tiny module with a GC struct type: only browsers with WasmGC accept it.
    const wasmGc = typeof WebAssembly === 'object' &&
        WebAssembly.validate(new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 95, 1, 120, 0]));
    // Read here: document.currentScript is only set while this file runs, not in later callbacks.
    // The version of this build (set by the server in index.html), so no browser takes an older
    // webApp.js from its cache after an update (Cloudflare keeps it there for hours).
    const version = new URL(document.currentScript.src).searchParams.get('v');

    function loadApp() {
        const script = document.createElement('script');
        script.src = 'webApp.js' + (version ? '?v=' + version : '');
        script.onerror = () => {
            note('webApp.js konnte nicht geladen werden');
            showProblem();
        };
        document.body.appendChild(script);
        setTimeout(() => {
            if (!ready) showProblem();
        }, 20000);
    }

    // WebKit 27.2 (Safari, and on iPhone/iPad every browser) miscompiles WebAssembly that runs often:
    // Compose apps stay white or draw wrongly. Reported by other Compose web apps in October 2026;
    // 27.1 and 27.3 are not affected. Nothing in the page can work around it, so it explains instead.
    const ua = navigator.userAgent;
    const ios = /(iPhone|iPad|iPod)/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
    const webkit272 = /(iPhone|iPad|iPod).* OS 27_2([_\s]|$)/.test(ua) ||
        (/Version\/27\.2(\.|\s|$)/.test(ua) && /Safari\//.test(ua) && !/(Chrome|Chromium|Edg|Firefox)\//.test(ua));
    let tryAnyway = false;
    try {
        tryAnyway = sessionStorage.getItem('waddeln.tryWebkit272') === '1';
    } catch (e) {}

    function showWebkitNotice() {
        const loading = document.getElementById('loading');
        if (loading) loading.style.display = 'none';
        const box = document.createElement('div');
        box.id = 'webkitNotice';
        box.style.cssText = 'position:fixed;inset:0;z-index:20;overflow:auto;padding:28px 22px;box-sizing:border-box;' +
            'background:#3B2A1A;color:#fff;font:16px/1.55 system-ui,sans-serif';
        const inner = document.createElement('div');
        inner.style.cssText = 'max-width:520px;margin:0 auto';
        const title = document.createElement('div');
        title.style.cssText = 'color:#FFD54F;font-weight:700;font-size:22px;line-height:1.3;margin-bottom:12px';
        title.textContent = ios ? 'Waddeln läuft auf iOS 27.2 leider nicht' : 'Waddeln läuft in Safari 27.2 leider nicht';
        const p1 = document.createElement('p');
        p1.textContent = (ios
            ? 'In iOS 27.2 steckt ein Fehler von Apple, der Spiele wie Waddeln im Browser stört: Die Seite bleibt weiß ' +
              'oder zeichnet falsch. Das betrifft auf dem iPhone und iPad alle Browser, auch Chrome und Edge.'
            : 'In Safari 27.2 steckt ein Fehler von Apple, der Spiele wie Waddeln im Browser stört: Die Seite bleibt ' +
              'weiß oder zeichnet falsch.');
        const p2 = document.createElement('p');
        p2.textContent = ios
            ? 'Was hilft: das nächste iOS-Update (Einstellungen → Allgemein → Softwareupdate). Bis dahin kannst du am ' +
              'Computer oder mit der Android-App spielen.'
            : 'Was hilft: das nächste Safari-Update – oder bis dahin ein anderer Browser wie Chrome, Edge oder Firefox.';
        const details = document.createElement('details');
        details.style.cssText = 'margin-top:14px;background:rgba(0,0,0,.3);border-radius:10px;padding:10px 14px';
        const summary = document.createElement('summary');
        summary.textContent = 'Für Bastler: so geht es trotzdem';
        summary.style.cssText = 'cursor:pointer;color:#FFD54F';
        const how = document.createElement('p');
        how.style.cssText = 'margin:10px 0 4px';
        how.textContent = (ios
            ? 'Einstellungen → Apps → Safari → Erweitert → Feature-Flags'
            : 'Safari → Einstellungen → Erweitert → „Funktionen für Webentwickler anzeigen“, dann Menü Entwickler → Feature-Flags') +
            ': „Experimental WebAssembly Debugger (Disables JIT)“ einschalten und Safari neu starten. ' +
            'Waddeln lädt dann langsamer, läuft aber. Danach unten auf „Trotzdem starten“ tippen.';
        details.append(summary, how);
        const button = document.createElement('button');
        button.textContent = 'Trotzdem starten';
        button.style.cssText = 'margin-top:18px;padding:12px 22px;border:0;border-radius:22px;background:#6D4C2F;' +
            'color:#fff;font:600 16px system-ui,sans-serif';
        button.onclick = () => {
            try {
                sessionStorage.setItem('waddeln.tryWebkit272', '1');
            } catch (e) {}
            box.remove();
            if (loading) loading.style.display = '';
            loadApp();
        };
        inner.append(title, p1, p2, details, button);
        box.append(inner);
        document.documentElement.appendChild(box);
    }

    if (wasmGc) {
        if (webkit272 && !tryAnyway) showWebkitNotice(); else loadApp();
    } else {
        document.getElementById('loading').remove();
        document.getElementById('unsupported').style.display = 'flex';
    }
})();
