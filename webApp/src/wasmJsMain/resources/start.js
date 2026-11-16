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
    if (wasmGc) {
        const script = document.createElement('script');
        script.src = 'webApp.js';
        script.onerror = () => {
            note('webApp.js konnte nicht geladen werden');
            showProblem();
        };
        document.body.appendChild(script);
        setTimeout(() => {
            if (!ready) showProblem();
        }, 20000);
    } else {
        document.getElementById('loading').remove();
        document.getElementById('unsupported').style.display = 'flex';
    }
})();
