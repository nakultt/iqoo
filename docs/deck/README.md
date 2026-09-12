# VeriTransit deck — "AI verification for B2B commerce"

A 19-slide, 16:9 pitch deck positioning VeriTransit as a **worker copilot for
physical shipment verification**, not a compliance product. Everything here is
generated from source — edit the source, re-run one command, get the deck back.

| | |
| --- | --- |
| Deliverable | [`VeriTransit-Worker-Copilot.pptx`](VeriTransit-Worker-Copilot.pptx) |
| Motion asset | [`motion/verification-flow/`](motion/verification-flow/) — `output.mp4` + `poster.png` |
| Chart data | [`data/charts.json`](data/charts.json) |
| Design system | [`lib/theme.js`](lib/theme.js) |

## Regenerate

```bash
cd docs/deck && npm install && node build.js
```

Charts are **native PowerPoint charts**, not images — a reader can click one and
edit the series in PowerPoint. Text is native text, so the whole deck stays
editable after generation.

## Re-render the animated workflow

The animation is a deterministic canvas scene: `window.render(t)` is a pure
function of time, so every frame is reproducible and there are no CSS
transitions to race.

```bash
node motion/verification-flow/render.js
```

Renders 450 frames (15s @ 30fps) with headless Chrome, muxes them to H.264 with
ffmpeg, and writes `poster.png` from the final frame. Requires Chrome at
`/Applications/Google Chrome.app` and `ffmpeg` on PATH. `build.js` embeds the
MP4 into slide 14 and uses the poster as its preview frame, so the slide is
still readable in print and PDF if nobody presses play.

To change the animation, edit `motion/verification-flow/index.html` — the scene
constants (`BOXES`, `LANES`, `LEDGER`, `OUTPUTS`) and the cue times on each
element are at the top of the scene section.

## Edit the numbers

`data/charts.json` holds every plotted series with its source line. Two series
are explicitly modelled, not measured, and are labelled ILLUSTRATIVE on the slide:

- `cycle` — minutes per inbound shipment, manual vs VeriTransit
- `detection` — where in the process an error is caught

Replace both with pilot measurements before using this deck with a customer.
The sourced figures are `frontline` (Zebra Warehousing Vision Study 2025) and
`exceptions` (Ardent Partners AP benchmark 2025).

## Design system

Palette is the product's own *Field Operational Precision* language, lifted from
[`Color.kt`](../../app/src/main/java/com/veritransit/inspector/ui/theme/Color.kt)
so the deck and the app read as one thing: alabaster canvas `#F4F3F0`, one
burgundy accent `#7E1530`, and the diagnostic emerald/amber/crimson used only
for status. Type is Aptos (ships with Microsoft 365) with Consolas for
machine-verified values. `lib/theme.js` exposes `frame`, `card`, `kpi`, `chip`,
`takeaway`, `node` and `arrow` — build new slides from those rather than
hand-placing shapes, so margins and title placement stay consistent.

Long titles are handled automatically: past 65 characters `frame()` drops the
title a size and moves the deck line down, so a two-line title never collides
with the subtitle.

## QA

`tools/pdf-preview.js` rasterises an exported PDF to PNGs so slide layout can be
checked without opening PowerPoint:

```bash
node tools/pdf-preview.js path/to/deck.pdf out-dir 1 19
```

Export the PDF from PowerPoint first (File → Save as PDF). Note that PowerPoint
on macOS is sandboxed: AppleScript exports land in
`~/Library/Containers/com.microsoft.Powerpoint/Data/`, not in the path you pass.

## Slide map

| # | Slide | Visual |
| --- | --- | --- |
| 1 | Title — AI-powered physical verification for B2B commerce | — |
| 2 | Every B2B transaction has two realities | Two-column + gap |
| 3 | The frontline says this work should be automated | Bar chart (Zebra) |
| 4 | Manual receiving creates the errors finance pays for | Error chain + doughnut + KPIs |
| 5 | Three things just became true | Three pillars |
| 6 | Today's stack checks documents, not goods | Comparison table |
| 7 | Open the shipment. Point the phone. Done. | Three steps |
| 8 | Four things happen in one camera pass | Four capability cards |
| 9 | Every unit is attributed — or it becomes an exception | Stacked bar + ledger |
| 10 | Three outcomes, one to act on | Verdict cards |
| 11 | The result becomes the record | Output grid |
| 12 | The commerce layer — the missing input to the 3-way match | Chain + two branches |
| 13 | Architecture — deterministic checks decide | Layered diagram |
| 14 | The workflow end to end | **Embedded MP4** + poster |
| 15 | The agent does not decide the match | Guardrail split |
| 16 | The demo — 30 seconds, one mismatch | Six-step timeline |
| 17 | Expected impact | Two charts (illustrative) |
| 18 | Built today vs next | Status + roadmap |
| 19 | Closing — the position | — |
