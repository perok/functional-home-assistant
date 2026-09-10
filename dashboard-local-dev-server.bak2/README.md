Why: To support PWA App
What: Split-Horizon DNS configuration

# Steps

## Step 1: Set Up Your Public Domain

1. Have a public domain (for.ex domoneshop) for.ex my.home.com
2. Setup the domain to point to a nameserver that supports ACME API (for.ex Cloudflare)
  - Follow a guide to setup your domain in Cloudflare
  - Get a Cloudflare API Token. Log in to your Cloudflare Dashboard. Go to My Profile > API Tokens. Click Create Token. Add permissions for "Zone Write" and "DNS Write"
    - Note down the token for later

## Step 2: Configure Your Local DNS Server (Split-Horizon)

1. Setup local DNS to point FQDN to homeassistnat

Find a guide that fits your router or setup of self hosted DNS server.

> In Unifi you can go to the client and add a local DNS address, or go to the
> policy settings for more detailed entries.


## Step 3: Setup TLS termination in HA

1. Add "NGINX proxy mananger" app in HA

2. Add certificate management in Nginx

> add SSL cloudflare and use
  dns_cloudflare_api_token=TOKEN

Add your public address. Add wildcard if you want subdomains as well: my.home.com, *.my.home.com

3. Setup reverse proxy in Nginx

> Add proxy host

- Want https directly to HA? Add homeassistant:8123 from your domain
- For FH dashboard: homeassistant:8080 from your domain for the dashboard. port set to app (8080)
- advanced; TODO setup gzip compression, higher compression for assets
- TODO brotli? https://github.com/ZoeyVid/NPMPlus https://github.com/ZoeyVid/NPMplus/discussions/3532


--
TODO
- use nginx proxyied version in cloudflare? home.perok.no for all?



