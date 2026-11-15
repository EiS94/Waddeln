// Loads the app only in browsers with WasmGC; the others get a notice instead of an empty page.
// A tiny module with a GC struct type: only browsers with WasmGC accept it.
const wasmGc = typeof WebAssembly === 'object' &&
    WebAssembly.validate(new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 95, 1, 120, 0]));
if (wasmGc) {
    const script = document.createElement('script');
    script.src = 'webApp.js';
    document.body.appendChild(script);
} else {
    document.getElementById('loading').remove();
    document.getElementById('unsupported').style.display = 'flex';
}
