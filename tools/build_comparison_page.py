"""Rebuilds the AtomicAmp vs Poweramp comparison artifact.

Kept in the repo rather than a scratch directory: the first copy lived in a temp folder, was
cleaned up, and the published page then could not be corrected when the facts changed under it.
"""
import base64
import os

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS = os.path.join(REPO, "store", "screenshots")
OUT = os.path.join(REPO, "store", "atomicamp-vs-poweramp.html")


def img(name):
    with open(os.path.join(SHOTS, name), "rb") as handle:
        return "data:image/png;base64," + base64.b64encode(handle.read()).decode()


ROWS = [
    ("Playback", [
        ("FLAC / hi-res", "yes", "Yes", "yes", "Yes, including 24/192"),
        ("Gapless", "yes", "Yes", "yes",
         "Measured: 88,200 frames across two files, none inserted"),
        ("10-band EQ + presets", "yes", "Yes, plus tone controls", "yes", "Yes, 10 bands + preamp"),
        ("Loudness levelling", "yes", "ReplayGain tags", "yes",
         "Measured live, because 0 of 3,350 files carry tags"),
        ("Crossfade", "yes", "Yes", "branch", "Built and up to date with main, still unheard"),
        ("Cue sheets", "yes", "Yes", "yes", "Local files, multi-FILE sheets left unsplit"),
    ]),
    ("Your own cloud", [
        ("Streams your storage", "unknown", "Not its model", "yes",
         "Backblaze B2, your credentials, no server in between"),
        ("Works offline after playing", "yes", "Downloads for offline", "yes",
         "Verified with wifi and mobile data switched off"),
        ("Art for streamed tracks", "unknown", "n/a", "yes", "Fetched once per album, on first play"),
        ("Cue sheets when streaming", "unknown", "n/a", "no",
         "Local only; 969 sheets in the bucket unhandled"),
    ]),
    ("Library", [
        ("Folder browsing", "yes", "Yes", "yes", "Yes"),
        ("Works with no DocumentsUI", "unknown", "Untested here", "yes",
         "Measured on the unit; carries its own browser"),
        ("Tag editor", "yes", "Yes", "yes", "FLAC, for files reachable by path"),
        ("Search / fast scroll", "yes", "Yes", "yes", "Yes, with an A-Z rail"),
    ]),
    ("In the car, and in the hand", [
        ("Resume at power-on", "yes", "Yes", "yes", "Verified across a reboot, at the right position"),
        ("Fullscreen artwork", "yes", "Yes", "yes", "Yes, both orientations"),
        ("Synced lyrics", "yes", "Yes", "yes", "Offline .lrc beside the track"),
        ("Sleep timer", "yes", "Yes", "yes", "Yes"),
        ("Home screen widget", "yes", "Yes", "yes", "Now playing plus transport"),
        ("Skins / visualisations", "yes", "Extensive", "no", "One dark theme, no visualisations"),
        ("Android Auto / Chromecast", "yes", "Yes", "skip",
         "Skipped on purpose &mdash; this <em>is</em> the head unit"),
    ]),
    ("As software", [
        ("Source you can change", "no", "Closed", "yes", "GPL-3.0"),
        ("Cost", "yes", "Paid unlock", "yes", "Free"),
        ("Automated tests", "unknown", "Unknown", "yes", "88 unit + 7 instrumented, two API levels"),
        ("On-device diagnostics", "no", "No", "yes", "Reports its environment and its own crashes"),
    ]),
]

CHIP = {"yes": "has", "no": "none", "branch": "part", "unknown": "unk", "skip": "skip"}


def cell(state, text):
    return ('<td class="c"><span class="chip %s" aria-hidden="true"></span>'
            '<span class="ct">%s</span></td>' % (CHIP[state], text))


table_rows = []
for group, items in ROWS:
    table_rows.append('<tr class="grp"><th colspan="3" scope="colgroup">%s</th></tr>' % group)
    for name, ps, pt, as_, at in items:
        table_rows.append('<tr><th scope="row">%s</th>%s%s</tr>'
                          % (name, cell(ps, pt), cell(as_, at)))
TABLE = "\n".join(table_rows)

STYLE = """
:root {
  --ground:#EDEFF1; --surface:#FFFFFF; --raised:#F5F7F8; --line:#D3D9DE;
  --ink:#12171B; --ink-2:#4E5860; --ink-3:#78838C; --accent:#8A5510;
  --has:#2F6B45; --none:#8A9098; --part:#8A5510; --unk:#6B6470; --skip:#4A5C6B;
  --frame:#C9D0D6;
  --mono: ui-monospace,"Cascadia Mono","SF Mono",Consolas,"Liberation Mono",monospace;
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    --ground:#0D0F11; --surface:#15181B; --raised:#1C2126; --line:#2A3138;
    --ink:#E7E9EB; --ink-2:#AEB6BD; --ink-3:#818A92; --accent:#FFB35C;
    --has:#7CC79A; --none:#767E86; --part:#FFB35C; --unk:#A79DB2; --skip:#8FA6B8;
    --frame:#2A3138;
  }
}
:root[data-theme="dark"] {
  --ground:#0D0F11; --surface:#15181B; --raised:#1C2126; --line:#2A3138;
  --ink:#E7E9EB; --ink-2:#AEB6BD; --ink-3:#818A92; --accent:#FFB35C;
  --has:#7CC79A; --none:#767E86; --part:#FFB35C; --unk:#A79DB2; --skip:#8FA6B8;
  --frame:#2A3138;
}
body {
  background:var(--ground); color:var(--ink); margin:0; font-size:16px; line-height:1.6;
  font-family:"Segoe UI Variable Text","Segoe UI",system-ui,-apple-system,sans-serif;
  -webkit-font-smoothing:antialiased;
}
.wrap { max-width:1120px; margin:0 auto; padding:56px 24px 96px; }
.eyebrow { font-family:var(--mono); font-size:12px; letter-spacing:.14em; text-transform:uppercase;
  color:var(--ink-3); margin:0 0 18px; }
h1 { font-family:var(--mono); font-weight:600; font-size:clamp(30px,5vw,46px); line-height:1.1;
  margin:0 0 16px; text-wrap:balance; }
h1 .amp { color:var(--accent); }
.lede { font-size:18px; color:var(--ink-2); max-width:64ch; margin:0 0 36px; }
.readout { display:grid; grid-template-columns:repeat(auto-fit,minmax(190px,1fr)); gap:1px;
  background:var(--line); border:1px solid var(--line); border-radius:3px; overflow:hidden;
  margin:0 0 64px; }
.readout div { background:var(--surface); padding:14px 16px; }
.readout dt { font-family:var(--mono); font-size:11px; letter-spacing:.12em;
  text-transform:uppercase; color:var(--ink-3); margin:0 0 5px; }
.readout dd { font-family:var(--mono); font-size:17px; margin:0; font-variant-numeric:tabular-nums; }
h2 { font-family:var(--mono); font-size:13px; letter-spacing:.16em; text-transform:uppercase;
  color:var(--accent); margin:0 0 6px; padding-top:8px; border-top:2px solid var(--accent);
  display:inline-block; }
.sec { margin:0 0 68px; }
.sec > p { max-width:66ch; color:var(--ink-2); margin:10px 0 26px; }
.sec h3 { font-size:21px; margin:0 0 8px; font-weight:600; }
.shots { display:grid; grid-template-columns:repeat(auto-fit,minmax(220px,1fr)); gap:26px; }
figure { margin:0; }
figure img { display:block; width:100%; height:auto; border:1px solid var(--frame);
  border-radius:6px; }
figcaption { font-family:var(--mono); font-size:11px; letter-spacing:.11em; text-transform:uppercase;
  color:var(--ink-3); margin:10px 0 0; }
.tablewrap { overflow-x:auto; border:1px solid var(--line); border-radius:3px;
  background:var(--surface); }
table { border-collapse:collapse; width:100%; min-width:720px; }
thead th { font-family:var(--mono); font-size:11px; letter-spacing:.13em; text-transform:uppercase;
  color:var(--ink-3); text-align:left; padding:13px 16px; border-bottom:1px solid var(--line);
  font-weight:600; }
thead th.mine { color:var(--accent); }
tr.grp th { font-family:var(--mono); font-size:11px; letter-spacing:.14em; text-transform:uppercase;
  color:var(--ink-2); background:var(--raised); padding:9px 16px; text-align:left; font-weight:600;
  border-top:1px solid var(--line); border-bottom:1px solid var(--line); }
tbody th[scope="row"] { text-align:left; font-weight:500; padding:12px 16px; width:26%;
  border-bottom:1px solid var(--line); vertical-align:top; }
td.c { padding:12px 16px; border-bottom:1px solid var(--line); color:var(--ink-2); font-size:15px;
  vertical-align:top; width:37%; }
.chip { display:inline-block; width:8px; height:8px; border-radius:50%; margin-right:9px;
  vertical-align:.06em; }
.chip.has { background:var(--has); }
.chip.none { background:transparent; border:1.5px solid var(--none); }
.chip.part { background:var(--part); }
.chip.unk { background:transparent; border:1.5px dashed var(--unk); }
.chip.skip { background:var(--skip); }
.legend { display:flex; flex-wrap:wrap; gap:18px; margin:14px 0 0; font-family:var(--mono);
  font-size:11px; letter-spacing:.09em; text-transform:uppercase; color:var(--ink-3); }
.cols { display:grid; grid-template-columns:1fr 1fr; gap:34px; }
@media (max-width:840px) { .cols { grid-template-columns:1fr; } }
.cols ul { margin:0; padding:0; list-style:none; }
.cols li { padding:13px 0; border-bottom:1px solid var(--line); font-size:15px; color:var(--ink-2); }
.cols li b { color:var(--ink); font-weight:600; display:block; margin-bottom:3px; }
.cols li:last-child { border-bottom:0; }
.note { border-left:2px solid var(--accent); background:var(--raised); padding:16px 20px;
  margin:34px 0 0; font-size:15px; color:var(--ink-2); border-radius:0 3px 3px 0; }
.note b { color:var(--ink); }
:focus-visible { outline:2px solid var(--accent); outline-offset:2px; }
"""

BODY = """
<div class="wrap">
  <p class="eyebrow">ATOTO S8 &middot; Android 10 &middot; and a Galaxy A37 &middot; Android 16</p>
  <h1>AtomicAmp <span class="amp">vs</span> Poweramp</h1>
  <p class="lede">
    Poweramp is a mature product with a decade of features. AtomicAmp is one app, built for one head
    unit, that now also runs on a phone and streams a personal cloud library. An honest account of
    where each one stands.
  </p>

  <dl class="readout">
    <div><dt>Local library</dt><dd>3,350 FLAC</dd></div>
    <div><dt>Cloud library</dt><dd>471 GB</dd></div>
    <div><dt>Document picker</dt><dd>absent</dd></div>
    <div><dt>Tests passing</dt><dd>88 + 7</dd></div>
  </dl>

  <section class="sec">
    <h2>What it looks like</h2>
    <h3>On a phone, at the owner's own 1.5&times; font scale</h3>
    <p>
      Screenshots come from a real handset rather than an emulator, so the type is the size someone
      actually reading it sees.
    </p>
    <div class="shots">
      <figure><img src="__SHOT1__" alt="Now Playing with album art"><figcaption>Now Playing</figcaption></figure>
      <figure><img src="__SHOT2__" alt="Fullscreen artwork"><figcaption>Fullscreen artwork</figcaption></figure>
      <figure><img src="__SHOT3__" alt="Library list"><figcaption>Library</figcaption></figure>
    </div>
  </section>

  <section class="sec">
    <h2>Feature by feature</h2>
    <h3>Where each one actually stands</h3>
    <p>
      Poweramp's column reflects general familiarity with the product, not a side-by-side install on
      this unit. AtomicAmp's column reflects what has been run and measured.
    </p>
    <div class="tablewrap">
      <table>
        <thead><tr><th scope="col">Capability</th><th scope="col">Poweramp</th><th scope="col" class="mine">AtomicAmp</th></tr></thead>
        <tbody>
__TABLE__
        </tbody>
      </table>
    </div>
    <p class="legend">
      <span><span class="chip has"></span>Has it</span>
      <span><span class="chip part"></span>On a branch</span>
      <span><span class="chip skip"></span>Skipped on purpose</span>
      <span><span class="chip unk"></span>Untested</span>
      <span><span class="chip none"></span>Not there</span>
    </p>
  </section>

  <section class="sec">
    <h2>The honest summary</h2>
    <div class="cols">
      <div>
        <h3>Where AtomicAmp wins</h3>
        <ul>
          <li><b>It runs where the picker does not exist</b>The unit ships no DocumentsUI at all, so it scans <code>file://</code> roots and carries its own folder browser.</li>
          <li><b>Your storage, not a service</b>Streams a B2 bucket with your own credentials and no server in between, and caches what it plays so losing signal is uneventful.</li>
          <li><b>Loudness matched to the actual library</b>Zero of 3,350 files carry ReplayGain tags, so level is measured from the audio instead.</li>
          <li><b>It tells you what is wrong</b>Diagnostics and a persisted crash log, on a device with no ADB and no Developer options.</li>
          <li><b>You can change it</b>GPL-3.0, and every decision was made for this dashboard.</li>
        </ul>
      </div>
      <div>
        <h3>Where Poweramp is still ahead</h3>
        <ul>
          <li><b>Breadth</b>Visualisations, a skin ecosystem, and years of format handling this does not have.</li>
          <li><b>Maturity</b>A decade of edge cases found on hardware nobody could enumerate.</li>
          <li><b>Cue sheets everywhere</b>AtomicAmp splits them for local files only; 969 sheets sit unhandled in the bucket.</li>
          <li><b>Crossfade that has been heard</b>AtomicAmp's is built and verified by measurement, and still nobody has listened to it.</li>
        </ul>
      </div>
    </div>

    <p class="note">
      <b>The fair reading:</b> Poweramp is the better music player. AtomicAmp is the better player
      <em>for this car</em> &mdash; not because it has more, but because a missing document picker
      and a personal 471&nbsp;GB bucket are the kind of problems only a program you own gets
      reshaped around.
    </p>
  </section>
</div>
"""

html = ("<title>AtomicAmp vs Poweramp</title>\n<style>" + STYLE + "</style>\n"
        + BODY.replace("__TABLE__", TABLE)
              .replace("__SHOT1__", img("1-now-playing.png"))
              .replace("__SHOT2__", img("2-fullscreen-art.png"))
              .replace("__SHOT3__", img("3-library.png")))

with open(OUT, "w", encoding="utf-8") as handle:
    handle.write(html)
print("written:", OUT, os.path.getsize(OUT), "bytes")
