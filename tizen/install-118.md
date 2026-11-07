# Installing on 2015–2016 Samsung TVs (Tizen 2.3 / 2.4): the `install failed[118]` fix

The README notes that some 2016 Full HD sets — e.g. the UE49K5670 (`16_HAWKM_FHD`), tracked in
[#349](https://github.com/simbasjungles/free-my-tv/issues/349) — "refuse developer installs altogether,
with `failed[118]` even from Samsung's own `tizen install`."

This turns out **not** to be a hardware refusal. On the same `16_HAWKM_FHD` platform (a UA40K5300,
`platform_version 2.4.0`) every `.wgt` — the official Samsung `AnalogWatch` sample included — failed with a
bare `install failed[118]`, and it was fixed. There are **two** independent causes, and both must be addressed:

## Cause 1 — signature digest is SHA‑512, which the Tizen 2.4 WRT rejects

Recent Tizen Studio signs the widget's `author-signature.xml` / `signature1.xml` with **SHA‑512**
(`http://www.w3.org/2001/04/xmlenc#sha512`, `…xmldsig-more#rsa-sha512`). The Web Runtime on Tizen 2.3/2.4
only validates **SHA‑256 / SHA‑1**, so signature verification fails and the installer returns `118`
(it runs for ~4 s first — it unpacks and verifies before rejecting).

**Fix — make the signer emit SHA‑256.** The digest URIs are string constants of equal length, so they can be
swapped in place (keep a backup):

```bash
# native signer used by `tizen package`
TZ="$HOME/tizen-studio/tools/tizen-core/tz"
cp "$TZ" "$TZ.sha512.bak"
perl -0777 -pi -e 's|xmlenc#sha512|xmlenc#sha256|g; s|xmldsig-more#rsa-sha512|xmldsig-more#rsa-sha256|g' "$TZ"
grep -oaE 'xml(enc|dsig)[a-z#-]*sha[0-9]+' "$TZ" | sort -u   # must print only sha256
```

Some Tizen Studio versions sign through Java instead
(`tools/ide/lib-ncli/org.tizen.common.sign_*.jar`, class `TizenIncrementalSigner`). The same two strings exist
there; patch that class too. **Important:** the CLI classpath globs `lib-ncli/`, so a `*.jar.sha512.bak` left
*next to* the jar shadows the patched jar and you still get SHA‑512 — keep any backup **outside** `lib-ncli/`.

Verify a freshly built package before installing:

```bash
unzip -p FreeMyTV-tizen.wgt author-signature.xml | grep -oE 'sha[0-9]+' | sort -u   # want sha256
```

## Cause 2 — use a Tizen certificate, not a Samsung certificate

On these old sets a **Samsung** distributor certificate (made via the Samsung Certificate Extension and tied to
the TV's DUID) still returns `118`, even with the DUID correctly registered and "permit to install" done.
A plain **Tizen** certificate works: a self‑signed author certificate plus the bundled Tizen public
distributor.

```bash
tizen certificate -a MyTV -p mypass -f author -o ~/TizenCert
AUTHOR=~/tizen-studio-data/keystore/author/author.p12
DIST=~/tizen-studio/tools/certificate-generator/certificates/distributor/tizen-distributor-signer.p12
tizen security-profiles add -n MyTV -a "$AUTHOR" -p mypass -d "$DIST" -dp tizenpkcs12passfordsigner
# then: python3 tizen/build.py --sign MyTV
```

This matches the earlier reports in #349 (KU6640 / KU6400) where the Tizen‑signed build installed and the
Samsung‑signed one did not — the difference is the newer HAWKM_FHD set *also* needed Cause 1 fixed.

## `config.xml` notes for Tizen 2.4

Free My TV's own [`config.xml`](config.xml) installed as it is on a KU6640 and a KU6400 (#349), with a space in its
`<name>` and `tv-samsung` as its profile, so change these only if the install fails in under a second. Three manifest
details also caused a **sub‑second** `install failed[118]` on a UA40K5300 (distinct from the ~4 s signature failure
above):

- **No spaces in `<name>`** — e.g. `NeoHome`, not `Neo Home`.
- **Drop `<tizen:profile name="tv"/>`** — its presence can be rejected here.
- **`application.info` privilege is rejected**; `application.launch` is accepted (public level).
- Keep `required_version="2.4"`, `screen.size.normal.1080.1920`, and a `<tizen:setting … hwkey-event="enable"/>`.

## Installing

`tizen install` on these TVs wants the **model name** as the target, not `host:port`:

```bash
sdb connect <TV_IP>:26101
tizen install -n FreeMyTV-tizen.wgt -t "$(sdb devices | awk '/device\t/{print $NF}')"   # e.g. -t UA40K5300
```

Arbitrary `sdb shell` is blocked (`closed`) on these sets; a few commands are whitelisted, e.g.
`sdb -s <TV>:26101 shell 0 was_execute <appId>` to launch, and `… getduid`.

## Gotcha: can't connect over sdb

If `sdb connect` returns `failed to connect to remote target` while port 26101 is open: **quit the Samsung
Certificate Manager GUI** first — it holds an sdb server with `--only-detect-tizen` that blocks the connection.
Then `sdb kill-server; sdb start-server; sdb connect <IP>:26101`. If the daemon is wedged, cold‑boot the TV
(unplug ~15 s) so it shows the "Samsung Tizen" splash, which re‑arms sdbd.

## Still platform‑limited

This makes a sideloaded `.wgt` **install and launch**. It does **not** make it the boot/home app: autostart and
home replacement need a platform‑level privilege a public certificate can't grant, and these sets don't root,
so the launcher opens manually from *Apps*.

---
*Verified 2026‑11 on UA40K5300 (`16_HAWKM_FHD`, Tizen 2.4.0), Tizen Studio 6.1 CLI on macOS.*
