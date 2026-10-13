# Native fallback resolver

`FallbackMediaResolver` is instance-owned by the caller (one per browser space); it has no static cross-space cache. It runs only after the caller has selected a concrete fallback URL and obtained consent.

* HTTPS only, port 443, no user-info, fragments, controls, redirects, cookies, or authorization headers.
* Both DNS preflight and the socket's connected `InetAddress` reject loopback, private, link-local, multicast, CGNAT, reserved IPv4, IPv6 ULA/link-local, and IPv4-mapped IPv6. A failed binding fails closed.
* Platform TLS certificate and hostname verification remain unchanged. There is no trust-all manager or permissive hostname verifier.
* The response is limited to 2 MiB and an 8-second total/connect/read budget. Only successful media URLs are cached, by full request URL, for 10 minutes (maximum 200 entries).
* The resolver preserves signed query fields byte-for-byte, replacing only `channel` with `no`, `codec_type` with `8`, and `logo_type` with `unwatermarked`. These request hints do **not** guarantee an unwatermarked result.
* It supports direct HTTPS, loose Base64 variants, and the v1.6.14 qAAB AES-CBC form only with a supplied `key_seed`; it does not guess keys, bypass DRM, transfer browser credentials, download media, or launch Chrome/CDP.

Returned media URLs have validated HTTPS syntax and a public DNS precheck. The caller must independently apply its download boundary before any fetch.

The native media panel lists up to eight passively observed candidate interfaces per navigation. Selecting one requires a separate host confirmation. The single 30s switch remains unchanged. No query is initiated by the page observer or scan loop. Successful results appear as interface-resolution results, not a guarantee of watermark removal. Pending queries are invalidated on navigation and activity shutdown; cache is memory-only.

Parity boundary: Chrome debugger attach/detach, Network.getResponseBody and Fetch.fulfillRequest remain unavailable. Original service-worker and content-panel files are not executed. Android deliberately refuses HTTP, private/special IPs and redirects; it does not forward browser credentials. Tests use synthetic payloads, not user accounts.
