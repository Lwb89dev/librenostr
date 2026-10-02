# LibreNostr Ostrich emoji pack

Six transparent 512×512 PNG custom-emoji assets built around the LibreNostr
mascot: a minimal purple ostrich face.

| Name | Intended meaning | File |
| --- | --- | --- |
| `ostrich_happy` | happy / celebration | `ostrich-happy.png` |
| `ostrich_love` | love / appreciation | `ostrich-love.png` |
| `ostrich_shocked` | surprise / disbelief | `ostrich-shocked.png` |
| `ostrich_thinking` | thinking / considering | `ostrich-thinking.png` |
| `ostrich_sleepy` | tired / good night | `ostrich-sleepy.png` |
| `ostrich_zap` | energy / zap / excitement | `ostrich-zap.png` |

The same files are bundled as Android `drawable-nodpi` resources with the
`librenostr_ostrich_*` names listed in `manifest.json`.

The current app does not yet expose a NIP-30 custom-emoji picker or renderer,
so this commit packages the assets and their stable names without changing the
existing text-rendering pipeline. For NIP-30 publication, host the PNGs at
stable HTTPS URLs and use the names from the manifest in `emoji` tags.

`preview.png` is a contact sheet for review and is not an emoji asset.
