// PWA bootstrap injected into every dashboard page (see Server.page):
//   1. registers the service worker (installability + offline shell), and
//   2. when running as the INSTALLED app, shows a small pill telling you
//      whether this session reached the server directly on the LAN or came in
//      over the internet.
//
// The direct-vs-internet answer comes from the server's `GET whoami`, which
// reflects the `X-FH-Via` header your reverse proxy stamps on each request
// (`local` for RFC1918/VPN clients, `internet` otherwise; absent = `direct`,
// e.g. loopback dev with no proxy). All URLs here are relative so they resolve
// against the page's <base href> (works under a bare domain and HA ingress).
(() => {
  if ('serviceWorker' in navigator) {
    addEventListener('load', () => {
      navigator.serviceWorker
        .register('pwa/sw.js', { scope: document.baseURI })
        .catch((err) => console.warn('[fh-pwa] service worker failed', err));
    });
  }

  // The connection pill is only meaningful for the installed app; a normal
  // browser tab (where the URL bar already shows where you are) stays clean.
  const standalone =
    matchMedia('(display-mode: standalone)').matches ||
    navigator.standalone === true;
  if (!standalone) return;

  const style = document.createElement('style');
  style.textContent = `
    #fh-conn{position:fixed;z-index:2147483000;pointer-events:none;
      left:max(8px,env(safe-area-inset-left));
      bottom:max(8px,env(safe-area-inset-bottom));
      display:flex;gap:6px;align-items:center;color:#fff;
      font:600 12px/1 system-ui,-apple-system,sans-serif;
      padding:6px 10px;border-radius:999px;opacity:.9;
      box-shadow:0 1px 4px rgba(0,0,0,.35)}
    #fh-conn[data-via=local]{background:#15803d}
    #fh-conn[data-via=internet]{background:#b45309}`;
  const pill = document.createElement('div');
  pill.id = 'fh-conn';
  pill.hidden = true;

  addEventListener('DOMContentLoaded', () => {
    document.head.appendChild(style);
    document.body.appendChild(pill);
    refresh();
  });

  async function refresh() {
    try {
      const r = await fetch('whoami', { cache: 'no-store' });
      const via = (await r.json()).via;
      const internet = via === 'internet';
      pill.dataset.via = internet ? 'internet' : 'local';
      pill.textContent = internet ? '☁️ Internet' : '🏠 Local';
      pill.hidden = false;
    } catch {
      pill.hidden = true; // offline / server unreachable: say nothing
    }
  }

  addEventListener('online', refresh);
  addEventListener('visibilitychange', () => {
    if (!document.hidden) refresh();
  });
})();
