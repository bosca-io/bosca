# Signing & notarizing the `bosca` macOS binary

This is the one-time setup so the native `bosca` binary you distribute is
**trusted by Gatekeeper** — i.e. nobody has to right-click → Open or visit
*System Settings → Privacy & Security → "Open Anyway"* to run it.

This is **Developer ID distribution**, not the Mac App Store. You host and
distribute the binary yourself; Apple has merely notarized it so macOS stops
warning that "the developer cannot be verified".

## What avoids the Gatekeeper prompt

macOS blocks a downloaded binary (one carrying the `com.apple.quarantine`
extended attribute) unless **both** are true:

1. It is **code-signed with a "Developer ID Application" certificate** with the
   **hardened runtime** enabled.
2. It has been **notarized** by Apple (uploaded, scanned, accepted).

An ad-hoc signature (what GraalVM emits by default) or an "Apple Development"
signature does **not** clear the prompt. You need the two steps above.

> A bare CLI binary cannot be *stapled* (the offline ticket attachment only
> works on `.app`/`.pkg`/`.dmg`). That's fine: a notarized binary is verified
> **online** on first launch, which is instant with any network connection.
> See [Offline / `.pkg`](#offline-stapling-optional-pkg) only if you need
> first-launch to work with no network at all.

---

## One-time setup

### 1. Apple Developer Program membership

You must be enrolled in the **Apple Developer Program** (paid, $99/yr) as an
Account Holder or Admin. A free Apple ID cannot create Developer ID certs.

Your **Team ID** is the 10-character code shown at
<https://developer.apple.com/account> (Membership details) — e.g. `AB12CD34EF`.

### 2. Create a "Developer ID Application" certificate

Easiest via Xcode:

1. **Xcode → Settings → Accounts**, sign in with your Apple ID.
2. Select your team → **Manage Certificates…**
3. Click **+** → **Developer ID Application**.
4. It is created and installed into your login keychain.

(Or create it manually at <https://developer.apple.com/account/resources/certificates>,
download the `.cer`, and double-click to import.)

Confirm it landed in your keychain and copy the exact identity string:

```bash
security find-identity -v -p codesigning
# Look for:  "Developer ID Application: Your Name (AB12CD34EF)"
```

### 3. Create notarization credentials — app-specific password (Option B)

This project is wired for the **app-specific password** route. The password is
the only secret; it never gets committed.

1. Create an **app-specific password** at <https://account.apple.com> → Sign-In
   & Security → App-Specific Passwords. Copy the `abcd-efgh-ijkl-mnop` value.
2. **For local builds**, store it once in a named keychain profile — the build
   already references the profile name `bosca-notary` (committed in
   the workspace root `gradle.properties`):

   ```bash
   xcrun notarytool store-credentials "bosca-notary" \
     --apple-id "developer@example.com" \
     --team-id "AB12CD34EF" \
     --password "abcd-efgh-ijkl-mnop"     # the app-specific password
   ```

   That tucks the password into your keychain. Local notarization then needs no
   env var — the build runs `notarytool … --keychain-profile bosca-notary`.

3. **For CI**, don't store a profile. Configure `MACOS_NOTARY_APPLE_ID` and
   `MACOS_NOTARY_TEAM_ID` for the build account, and supply the app-specific
   password as `MACOS_NOTARY_PASSWORD` (a CI secret). The build
   passes it to notarytool as `--password "@env:MACOS_NOTARY_PASSWORD"`, so the
   secret is read from the environment and never appears in the command line.

> The App Store Connect **API key** (Option A) remains supported as a fallback
> via `bosca.macos.notaryApiKeyPath` / `…ApiKeyId` / `…ApiIssuer` if you ever
> prefer it, but you don't need it for this route.

### 4. Credentials & properties

The repository supplies the `.pkg` bundle identifier and the notary
keychain-profile name (`bosca-notary`). Configure signing identities and account
identifiers in your user-level `~/.gradle/gradle.properties` or through the
environment variables below. Keep account-specific settings out of the repository.

1. **The signing certificates must be in a keychain on the build host.** The
   configured identity names only *resolve* if the matching cert + private key
   are installed (steps 1–2). Locally that's your login keychain; in CI it's an
   imported temporary keychain (see [CI](#continuous-integration)).
2. **The app-specific password.** Local: stored in the keychain by step 3 — no
   gradle config needed. CI: the `MACOS_NOTARY_PASSWORD` secret (below).

Gradle properties take precedence over their corresponding environment variables:

| Gradle property                      | Environment variable               |
|--------------------------------------|------------------------------------|
| `bosca.macos.signIdentity`           | `MACOS_SIGN_IDENTITY`              |
| `bosca.macos.installerSignIdentity`  | `MACOS_INSTALLER_SIGN_IDENTITY`    |
| `bosca.macos.notaryKeychainProfile`  | `MACOS_NOTARY_KEYCHAIN_PROFILE`    |
| `bosca.macos.notaryAppleId`          | `MACOS_NOTARY_APPLE_ID`            |
| `bosca.macos.notaryTeamId`           | `MACOS_NOTARY_TEAM_ID`             |
| *(app-specific password — secret)*   | `MACOS_NOTARY_PASSWORD`            |

The build uses the Apple ID form when the Apple ID, Team ID, and password are all
configured. Otherwise, it uses a configured keychain profile before falling back
to an API key.

---

## Continuous integration (Bosca pipeline)

The [CLI release pipeline](../.bosca/pipelines/release-cli.yaml) builds, signs,
and notarizes the `.pkg` on the `macos` agent and publishes it to the GitHub
release `cli-v<version>` as `bosca-<version>-macos-arm64.pkg`.

The certs and the `bosca-notary` profile already live on the persistent `macos`
agent — the only thing CI has to do that a logged-in workstation gets for free
is **unlock the keychain**. On a head-less box the login keychain is locked, so
codesign/notarytool would pop the GUI *"enter your password to unlock"* dialog
(answerable only over VNC). The *Unlock signing keychain* step handles it
non-interactively:

```bash
security set-keychain-settings "$KEYCHAIN"                          # no auto-lock during the run
security unlock-keychain -p "$MACOS_KEYCHAIN_PASSWORD" "$KEYCHAIN"  # head-less unlock
```

### One secret to set on the pipeline

| Secret | What it is |
|---|---|
| `MACOS_KEYCHAIN_PASSWORD` | the password that unlocks the keychain holding the certs + `bosca-notary` profile (the agent user's **login-keychain password** by default) |

By default the step unlocks `~/Library/Keychains/login.keychain-db`; set the
pipeline's `SIGNING_KEYCHAIN` env to unlock a different keychain. Because the
login keychain password is usually the account password, prefer a **dedicated
signing keychain** on the agent (its own throwaway password as the secret) if
you don't want the login password in CI.

The agent also needs SDKMAN installed (the pipeline installs `25.0.3-graal` via
`setup-java`) and ≥ 16 GB RAM free for `native-image`.

The artifact upload authenticates with the agent token surfaced by
`setup-registry` (`BOSCA_REGISTRY_TOKEN`), so no artifact secret is required —
provided that token has `raw:push` permission on the namespace. If it doesn't,
mint a scoped token, add it as an `ARTIFACTS_API_TOKEN` secret, and use it in
the publish step instead.

> Tagging: add a `sign-and-publish` job to the root release flow once the
> shared repository's release scope is decided.

---

## Building a signed + notarized binary

Run these commands from the workspace root.

```bash
./gradlew :cli:distNativeMacos
```

That runs, in order:

1. `nativeCompile` — produce `build/native/nativeCompile/bosca`.
2. `signNativeMacos` — Developer ID signature, hardened runtime, secure
   timestamp, using `signing/entitlements.plist`.
3. `packageNativeMacosZip` — `ditto`-zip the signed binary for upload.
4. `notarizeNativeMacos` — `xcrun notarytool submit … --wait` (blocks until
   Apple returns `Accepted` or `Invalid`).
5. `verifyNativeMacos` — `codesign --verify --strict` sanity check.

Run individual steps if you prefer (`./gradlew :cli:signNativeMacos`, etc.).

### Confirm it worked

```bash
# TeamIdentifier should be set; flags should include 'runtime':
codesign -dvvv build/native/nativeCompile/bosca

# Simulate a downloaded file and confirm Gatekeeper accepts it:
xattr -w com.apple.quarantine "0081;0;manual;" build/native/nativeCompile/bosca
spctl -a -vvv -t install build/native/nativeCompile/bosca   # → "accepted ... Notarized Developer ID"
xattr -d com.apple.quarantine build/native/nativeCompile/bosca
```

If notarization is rejected, fetch the detailed log:

```bash
xcrun notarytool log <submission-id> --keychain-profile "bosca-notary"
```

---

## Entitlements

`signing/entitlements.plist` carries the hardened-runtime exceptions a GraalVM
native image typically needs (`allow-jit`, `allow-unsigned-executable-memory`,
`disable-library-validation`). They do not affect notarization. If `bosca` runs
fine with one removed, drop it and re-sign — fewer entitlements is stronger.

**Keep that file comment-free.** `codesign` parses entitlements with Apple's
strict `AMFIUnserializeXML`, which rejects things a normal XML reader tolerates —
notably any XML comment containing a `--` (e.g. a flag like `--options`). A bad
comment fails signing with `AMFIUnserializeXML: syntax error near line N`. Put
explanations here, not in the plist.

---

## Offline-stapled `.pkg` installer

A bare binary verifies online. If you need first-launch to succeed with **no
network**, ship a notarized, **stapled** `.pkg` instead — the ticket travels
inside the package, so Gatekeeper never has to phone home. The installer drops
`bosca` into `/usr/local/bin`.

```bash
./gradlew :cli:distNativeMacosPkg
# → build/native/nativeCompile/bosca.pkg (signed, notarized, stapled)
```

That runs: `signNativeMacos` → `stageNativeMacosPkgRoot` → `packageNativeMacosPkg`
→ `notarizeNativeMacosPkg` → `stapleNativeMacosPkg` → `verifyNativeMacosPkg`.

### Extra requirement: a "Developer ID Installer" certificate

A `.pkg` is signed by a **different** cert than the binary — *Developer ID
**Installer*** (the binary uses *Developer ID **Application***). Create it the
same way as in step 2 (Xcode → Manage Certificates → **+** → *Developer ID
Installer*), then point Gradle at it:

```properties
bosca.macos.installerSignIdentity=Developer ID Installer: Your Name (AB12CD34EF)
# optional overrides (defaults shown):
# bosca.macos.pkgIdentifier=io.bosca.cli
# bosca.macos.pkgVersion=<project version>
```

| Gradle property                      | Environment variable               |
|--------------------------------------|------------------------------------|
| `bosca.macos.installerSignIdentity`  | `MACOS_INSTALLER_SIGN_IDENTITY`    |
| `bosca.macos.pkgIdentifier`          | —                                  |
| `bosca.macos.pkgVersion`             | —                                  |

Notary credentials (profile or API key) are shared with the bare-binary path —
no extra setup. Confirm the result:

```bash
pkgutil --check-signature build/native/nativeCompile/bosca.pkg   # signing chain
xcrun stapler validate     build/native/nativeCompile/bosca.pkg   # ticket attached
spctl --assess -vvv --type install build/native/nativeCompile/bosca.pkg
```
