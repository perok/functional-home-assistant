# Install the dashboard as a phone app (with local + remote access)

This guide turns the FH Dashboard into an **installable phone app** (a PWA) reachable by a
**single URL** that works both at home (directly, over your LAN) and away (over the
internet), and shows you which of the two you're currently on.

> **TL;DR — the "route A" design.** One hostname (`dash.example.com`). At home your local
> DNS points it at the LAN; away, public DNS points it at your reverse proxy. Same origin
> both times, so the app installs once and just works. The switching happens in the
> network, not in the app — there is no in-app URL toggle to get wrong. A small pill in the
> installed app tells you whether this session came in **🏠 Local** or **☁️ Internet**.

If you have **Ubiquiti/UniFi** gear, read the concepts here and then jump to the
[UniFi walkthrough](#unifi-walkthrough) for the exact clicks.

---

## How it works

```
                 ┌────────── away ──────────┐        ┌────────── home ──────────┐
 phone (PWA)     │ public DNS: WAN IP        │        │ local DNS: LAN IP         │
      │          │                           │        │                           │
      ▼          ▼                           │        ▼                           │
  dash.example.com ─── TLS ──► reverse proxy ───────► FH Dashboard add-on (:8080)
                              (wildcard cert,          (direct port, unauthenticated)
                               sets X-FH-Via)
```

- **One hostname, split-horizon DNS.** `dash.example.com` resolves to the reverse proxy's
  **LAN IP at home** and to your **public IP (or tunnel) away**. Because the origin is
  identical, the installed PWA never has to know where it is.
- **The reverse proxy terminates TLS** with a wildcard cert and forwards to the add-on's
  **direct HTTP port 8080** (`ingress_port` in `home-addon/config.yaml`; the same port the
  add-on optionally publishes as `8080/tcp`).
- **The proxy stamps `X-FH-Via`** on every request — `local` when the client's source IP is
  private (LAN/VPN), `internet` otherwise. The app reads it from `GET /whoami` and shows the
  pill. No header at all (e.g. loopback dev) reads back as `direct`.
- **Live updates keep working remotely** because the whole thing is one long-lived SSE
  stream; you just need the proxy to not buffer the `/sse/` path (below).

### Why not just use HA ingress?

The add-on is also an **ingress panel** inside Home Assistant, so it already rides wherever
HA goes (Nabu Casa, your HA reverse proxy, the companion app). That's great for casual use,
but the ingress URL is a per-session token path (`…/api/hassio_ingress/<token>/`) — not a
stable, installable `start_url`, and service-worker scope under ingress is unreliable. For a
**standalone installable app with its own icon and URL**, use the direct-port + reverse-proxy
path this guide describes. (Both can coexist.)

---

## Prerequisites

- A **domain you control** (for a real TLS cert; `.local`/`.lan` names can't get one and an
  installed HTTPS PWA refuses to talk to an invalid-cert / plain-HTTP endpoint).
- A **reverse proxy** that can terminate TLS *and* set a request header: Caddy, nginx,
  Traefik, Nginx Proxy Manager, or the **"NGINX Home Assistant SSL proxy"** add-on. It must
  be able to reach the add-on's port 8080.
- The ability to add **local DNS overrides** (your router, Pi-hole/AdGuard, or UniFi).
- The FH Dashboard add-on running. To let an external proxy reach it, publish the host port
  (add-on → **Configuration → Network → 8080** → set a host port) *or* run the proxy on the
  same host so it can reach the container directly.

> **⚠️ The direct port is unauthenticated.** The add-on drives Home Assistant with its own
> privileged token and has no login on the direct port. Anything that can reach port 8080
> can control your home. So: keep 8080 reachable **only** by the reverse proxy (LAN firewall
> / same-host), and read [Security](#security) before exposing anything to the internet.

---

## Step 1 — Wildcard TLS cert (DNS-01)

Get `*.example.com` from Let's Encrypt using a **DNS-01 challenge** (Caddy with a DNS
plugin, `acme.sh`, or certbot's DNS plugins). DNS-01 needs no inbound ports and works for
names that only resolve on your LAN — so the same cert is valid at home and away, and covers
as many subdomains as you like.

## Step 2 — Reverse proxy (TLS + `X-FH-Via`)

Route `dash.example.com` → `http://<addon-host>:8080`, classify the client, disable SSE
buffering.

**Caddy** (SSE works out of the box; `private_ranges` is built in):

```caddy
dash.example.com {
    tls {
        dns cloudflare {env.CF_API_TOKEN}   # your DNS provider's plugin
    }

    @local remote_ip private_ranges
    handle @local {
        reverse_proxy 10.0.0.10:8080 {
            header_up X-FH-Via local
        }
    }
    handle {
        reverse_proxy 10.0.0.10:8080 {
            header_up X-FH-Via internet
        }
    }
}
```

**nginx**:

```nginx
# private/VPN source IPs → local, everything else → internet
map $remote_addr $fh_via {
    default                                          internet;
    "~^10\."                                         local;
    "~^192\.168\."                                   local;
    "~^172\.(1[6-9]|2[0-9]|3[01])\."                 local;
    "~^127\."                                        local;
}

server {
    listen 443 ssl http2;
    server_name dash.example.com;
    ssl_certificate     /etc/letsencrypt/live/example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/example.com/privkey.pem;

    location / {
        proxy_pass http://10.0.0.10:8080;
        proxy_set_header Host              $host;
        proxy_set_header X-Forwarded-For   $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-FH-Via          $fh_via;

        # SSE: keep the stream open and unbuffered
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_read_timeout 1h;
        proxy_buffering off;
    }
}
```

> **Behind Cloudflare Tunnel or another proxy?** `$remote_addr` becomes the upstream proxy's
> IP, so the classification breaks. Use the real client IP header it provides
> (`CF-Connecting-IP` for Cloudflare) in the `map`, and remember that a tunnel means home
> traffic also exits to the internet unless your split-horizon DNS sends it straight to the
> LAN proxy instead.

## Step 3 — Public DNS + inbound (the "away" path)

- Public `A`/`AAAA` record `dash.example.com` → your WAN IP, and forward **443 → the reverse
  proxy**. Add **DDNS** if your WAN IP is dynamic.
- Or skip public exposure entirely with a **VPN** (see the UniFi WireGuard option below) or a
  tunnel (Cloudflare Tunnel / Tailscale Funnel).

## Step 4 — Local DNS override (the "home" path, = split-horizon)

Make `dash.example.com` resolve to the **reverse proxy's LAN IP** for clients on your
network. This is the whole trick: same name, LAN answer at home, public answer away. Do it
on whatever is your LAN resolver (router, Pi-hole/AdGuard, or UniFi — see below).

## Step 5 — Install on the phone

Open `https://dash.example.com`, then **Add to Home Screen / Install app**. Launched from the
home screen it runs full-screen, and the pill in the corner shows **🏠 Local** at home,
**☁️ Internet** away. (The pill only appears in the installed app, not in a browser tab.)

> **iOS icon note.** The app ships **SVG** icons, which Android/Chromium install with
> perfectly. iOS is happiest with a PNG `apple-touch-icon`; if the home-screen icon looks
> off on iOS, drop a `180×180` and `512×512` PNG into
> `modules/fh-datastar-view/src/main/resources/pwa/` and point the `apple-touch-icon`
> `<link>` / manifest `icons` at them.

---

## Security

Exposing the direct port through a proxy makes routes reachable that are safe only on
loopback. Before the proxy faces the internet:

- **Block the editor + language server on the public vhost.** Deny `/edit`, `/edit/*`, and
  `/lsp/*` at the proxy (they write dashboard source and spawn a language server). Example
  (nginx): `location ~ ^/(edit|lsp)(/|$) { return 404; }`.
- **Put authentication in front of actions.** `POST /sse/action/*` drives HA with a
  privileged token and has no auth of its own. Add proxy auth (Authelia / OAuth2-proxy /
  basic auth), or don't expose the internet path at all and reach it only over VPN.
- **Keep port 8080 private.** Only the reverse proxy should reach it — LAN firewall rule or
  proxy co-located on the HA host. Never port-forward 8080 itself.
- `GET /health` and `GET /whoami` are safe to expose (liveness + a three-value connection
  tag); `/whoami` only ever echoes one of `local` / `internet` / `direct`.

---

## <a id="unifi-walkthrough"></a>Ubiquiti / UniFi walkthrough

Assumes a UniFi gateway (UDM / UDM-Pro / UDR / Cloud Gateway) running UniFi Network. UniFi
gives you the **DNS** and **WAN/VPN** pieces cleanly; run the **reverse proxy** on your HA
host (UniFi has no first-class reverse proxy with header injection).

### A. Local DNS override (split-horizon)

**UniFi Network → Settings → Routing → DNS → Local DNS Records** (older firmware:
**Settings → Networks →** the network **→ Advanced → Local DNS**). Add:

| Type | Hostname            | Value (IP)              |
|------|---------------------|-------------------------|
| A    | `dash.example.com`  | LAN IP of the proxy, e.g. `10.0.0.10` |

Now every LAN client that uses the UDM as its DNS resolver gets the LAN IP for that name,
while off-network it resolves publicly. That *is* split-horizon.

- **Running Pi-hole/AdGuard as your LAN DNS instead?** Add the override there — UniFi's
  record only applies to clients resolving through the gateway.
- No DNS-rebinding exception is needed here: you're resolving the name to a **LAN** IP, not a
  public IP that points inward.

### B. Remote access — pick one

**Option 1 — WireGuard VPN (recommended; zero public exposure).**
**Settings → VPN → VPN Server → WireGuard**, create a server, add the phone as a client
(scan the QR in the WG app). When you're out, turn on the VPN and you're logically **on the
LAN** — the same local DNS record and LAN IP work, nothing is exposed to the internet, and
the add-on's unauthenticated port is never publicly reachable. This is the lowest-risk setup;
use it unless you specifically need a shareable public URL.

- On VPN, your source IP is the WireGuard subnet (private), so the pill reads **🏠 Local** —
  which is fair: you're on a trusted, encrypted path. If you'd rather it read *Internet* over
  VPN, just don't add a local DNS record for the VPN client subnet.

**Option 2 — Public port-forward (needed only for a truly public URL).**
- **Settings → Security → Port Forwarding** (or **Firewall → NAT**): forward WAN **443** →
  the proxy's LAN IP `:443`.
- **Settings → Internet →** your WAN **→ Dynamic DNS** if your IP changes; point the public
  `A` record for `dash.example.com` at it.
- Add a firewall policy to restrict/monitor it, and **do the [Security](#security) steps** —
  a public port-forward makes the unauthenticated add-on reachable to the world unless the
  proxy blocks `/edit`, `/lsp`, and gates `/sse/action/*`.

### C. Where the reverse proxy lives

Run Caddy/NPM or the **NGINX Home Assistant SSL proxy** add-on on your HA host. UniFi
handles DNS + WAN/VPN; the proxy handles TLS termination and the `X-FH-Via` header. Point it
at the FH Dashboard add-on's port 8080 (publish the host port, or reach the container over
the local Docker network if co-located).

**Net recommendation for UniFi users:** Local DNS record (A) + WireGuard (B-1) + a small
Caddy reverse proxy (C). That gives you the installable app, the correct 🏠/☁️ indicator, and
remote access **without exposing anything to the internet**.
