# The Android signing key

Every released APK is signed with one key (certificate SHA-256
`0e12358c264f8fa04e448098cb1ed0302256a1e749018a2d2065fea17167ec05`, pinned in
`release.yml` and `signing-key.yml`), so a new release installs over the old
one. The key is never rotated: installed apps would refuse an APK with
another certificate.

Where the key lives and who can use it:

- The repository secret `ANDROID_RELEASE_KEYSTORE_BASE64` (the PKCS12
  keystore as base64; alias `release`, password `android`). It is not in the
  repository and no Actions cache holds it.
- `ci.yml` and the build job of `release.yml` sign with a throwaway debug key
  made on the runner. Their APKs do not install over a released app.
- `release.yml` job "Android APK (sign)" is the only job that reads the
  secret. It runs no project code: it downloads the built APK, re-signs it
  with `apksigner`, checks the certificate against the pin and deletes the
  key. It runs only after CI is green on the exact commit.
- `signing-key.yml` runs daily and fails when the secret is missing or holds
  another key.

## Setting or restoring the secret

1. Take the keystore's base64 text (`base64 -w0 betterdriver-release.keystore`,
   on macOS `base64 -i betterdriver-release.keystore | tr -d '\n'`).
2. GitHub > Settings > Secrets and variables > Actions > New repository
   secret: name `ANDROID_RELEASE_KEYSTORE_BASE64`, paste the text.
3. Actions > "Keep the Android signing key" > Run workflow must be green.
4. Keep your own offline copy of the keystore (for example a password manager
   attachment): a repository secret cannot be read back.
