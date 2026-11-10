# Sources

Administration of the RSS and Atom feeds the ingestion reads, with a check of each new feed. Code: `swipread.api.source`.

## Endpoints

| Endpoint | Does |
| --- | --- |
| `GET /v1/admin/sources` | All sources, sorted by name |
| `POST /v1/admin/sources` | Checks the feed, then saves the source as active |
| `PATCH /v1/admin/sources/{id}` | Activates or deactivates (`active` is the only editable field) |
| `DELETE /v1/admin/sources/{id}` | Deletes a source that has no articles |

All require the `ADMIN` role. Details: [API reference](../api.md#administration).

## Adding a source

`SourceAdminService.create`:

1. Rejects a `feedUrl` already used (409). The unique constraint on `app.sources.feed_url` also catches concurrent requests.
2. Downloads and parses the feed with `FeedProbe.countEntries`, **outside any transaction**, so no database connection is held during the network call.
3. Refuses a feed with no entries (422 `"Feed has no articles"`).
4. Saves the source (`license` empty → `null`), `active: true`.

`RomeFeedProbe` implements the check:

| Limit | Value |
| --- | --- |
| Connect timeout | 5 s |
| Response timeout | 10 s (per request) |
| Body size | 5 MB |
| Redirects | 5, followed manually |
| Parser | Rome 2.1 (`SyndFeedInput`), RSS and Atom; `DOCTYPE` refused, so no external entities (XXE) |

Failures map to 422 with an English `detail` the admin UI can show or translate: `Feed is unreachable`, `Feed answered HTTP <status>`, `Not a valid RSS or Atom feed`, `Feed is larger than 5 MB`, `Feed redirects too many times`, `Feed address is not allowed`.

## SSRF protection

The API downloads a URL typed by a user. Without protection, an admin account (or an attacker who stole one) could make the server call internal services or the cloud metadata endpoint.

`FeedAddressPolicy` enforces:

- **Scheme and port** (`check(uri)`, at each hop): `http` or `https` only, ports 80 and 443 only.
- **Address** (`resolve(host)`): every address the host resolves to must be public. Refused: loopback, unspecified, private (10/8, 172.16/12, 192.168/16), CGNAT (100.64/10), link-local (169.254/16 including cloud metadata, fe80::/10), multicast and reserved (224/4 and above), IPv6 unique local (fc00::/7), and IPv6 addresses that embed a forbidden IPv4 (`::ffff:a.b.c.d`, `::a.b.c.d`, NAT64 `64:ff9b::/96`). One forbidden address is enough to refuse the host.
- **Redirects** are not followed by the HTTP client: `RomeFeedProbe` follows them itself, checking scheme, port and address of every target.
- **DNS rebinding.** The probe uses Apache HttpClient 5 with `FeedAddressPolicy.resolve` as its DNS resolver. The addresses that are checked are the ones the connection uses, so a domain cannot answer a public address to the check and a private one to the connection. No system proxy is used (a proxy would resolve the host itself).

`swipread.sources.allow-local-feeds=true` lifts the address and port checks (not the scheme check). It is set in the `test` profile only, because integration tests serve feeds from `localhost` on random ports.

## Deactivating and deleting

- An inactive source is skipped by the ingestion; its published articles stay in feeds, saved lists and history.
- Deleting a source that has articles answers 409: deleting its articles would erase readers' saved lists and history (cascades). Deactivate it instead.

## Tests

`AdminSourcesIntegrationTest` (creation with a checked feed served locally, 409 on a used feed, 422 on empty or failing feeds, deactivation, deletion and 409, 403 for readers), `RomeFeedProbeTest` (RSS and Atom, empty feed, HTML, 404, unreachable host, external entities refused, redirects checked at each hop, redirect loop, local feed refused by default, connection through the policy's resolver, rebinding refused at connection time), `FeedAddressPolicyTest` (IPv4 and IPv6 ranges, public addresses, ports, schemes, one internal address among several, unknown host).
