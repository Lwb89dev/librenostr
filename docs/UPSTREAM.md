# LibreNostr upstream origin

LibreNostr is a fork of the open-source Primal Android client. This document records the legal origin of the code, the git remotes, and the architectural direction of the fork.

This file does **not** claim original authorship of upstream code.

## Upstream repository

| Field | Value |
|---|---|
| Repository | [PrimalHQ/primal-android-app](https://github.com/PrimalHQ/primal-android-app) |
| Product name | Primal |
| License | MIT |
| Copyright | Copyright (c) 2023 PRIMAL SYSTEMS INC. |
| Initial base commit | `efb88b5af1db9d84eb36b471bf17d49d1c8a8a0c` |
| Initial base tag/message | `Primal 3.5.25 release` (2026-07-28) |
| Import date | 2026-08-27 |

The full upstream git history was imported. LibreNostr starts from that commit rather than a squashed snapshot, so later `git merge` / `git rebase` from `upstream` remains possible.

## License obligations

The MIT license requires that the copyright notice and permission notice be retained in all copies or substantial portions of the Software.

LibreNostr **must**:

- keep `LICENSE` with the PRIMAL SYSTEMS INC. copyright;
- keep the MIT permission notice;
- not claim original authorship of unmodified or derived upstream code;
- not reuse Primal trademarks, logos, store copy, or branding in the shipped product.

LibreNostr **may**:

- modify, rebrand, and redistribute the code under MIT;
- add additional copyright lines for original LibreNostr work;
- remove Primal user-facing branding after the notices above are preserved.

The current `LICENSE` file is unchanged from upstream and remains the legal notice of record.

## Destination repository

| Field | Value |
|---|---|
| GitHub repository | [Lwb89dev/librenostr](https://github.com/Lwb89dev/librenostr) |
| Local `origin` | `https://github.com/Lwb89dev/librenostr.git` |
| Local `upstream` | `https://github.com/PrimalHQ/primal-android-app.git` |
| `upstream` push URL | `DISABLE` (push to Primal is blocked on purpose) |

Status on 2026-08-27:

- GitHub repository created as `Lwb89dev/librenostr` (private).
- `origin` points at that URL.
- First push of `main` is the imported Primal 3.5.25 history plus LibreNostr Phase 0 docs.

Do **not** push to `upstream`.

## Recommended remote setup

```text
origin    → Lwb89dev/librenostr          (LibreNostr work)
upstream  → PrimalHQ/primal-android-app  (fetch only)
```

Verify:

```bash
git remote -v
# origin    https://github.com/Lwb89dev/librenostr.git (fetch)
# origin    https://github.com/Lwb89dev/librenostr.git (push)
# upstream  https://github.com/PrimalHQ/primal-android-app.git (fetch)
# upstream  DISABLE (push)
```

## History tradeoff

Preserving full upstream history was chosen because:

- later cherry-picks and merges from Primal remain practical;
- `git blame` still points at real upstream authors;
- MIT attribution is easier to audit.

The cost is a larger repository and a history that still talks about Primal. That is acceptable for a fork. Squashing was not necessary.

## Major architectural changes planned by LibreNostr

These changes are **not** in the imported baseline. They are the intended direction of the fork. See `docs/LIBRENOSTR_ROADMAP.md`.

1. Stop using Primal cache/aggregation servers as the primary source of Nostr data.
2. Read and write through a relay pool using standard NIP WebSocket messages.
3. Keep local Room/DataStore caching on the device.
4. Remove Primal trending/discovery that has no protocol equivalent.
5. Remove external signer product features (NIP-46 client/provider UI, NIP-55 Amber/client+provider) after local `nsec` identity is confirmed.
6. Remove Primal membership/premium/paid-service features.
7. Rebrand the application as LibreNostr without deleting legally required notices.
8. Do not replace the Primal cache with a new LibreNostr aggregation server.

## Related documents

- `docs/BASELINE.md` — build/test record of the unmodified import
- `docs/ARCHITECTURE_UPSTREAM.md` — KEEP / REFACTOR / REPLACE / REMOVE / INVESTIGATE map
- `docs/PRIMAL_SERVER_DEPENDENCIES.md` — inventory of cache/server verbs
- `docs/LIBRENOSTR_ROADMAP.md` — phased migration
- `docs/LIBRENOSTR_BACKLOG.md` — atomic tasks
