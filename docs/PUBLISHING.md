# Publishing this source

Publish the standalone project only. The parent development workspace contains
private test material and is not intended for publication.

This document describes preparation steps; it does not mean a public source
archive, Git bundle, or release artifact has already been created. Prepare a
new source-only directory from the intended current project files. Do not copy
the parent workspace or carry its `.git` history into the public repository.
Include the reviewed tracked changes and intended non-ignored new source files;
exclude `.git`, ignored local configuration, caches, build outputs, signing
stores, APKs, device captures, and unrelated workspace files. Review the copied
file list before initializing fresh Git history.

Before committing:

```sh
git status --short
git diff --cached --check
git diff --check
python3 tools/audit_public.py
```

Set an intentional author and committer identity for the new history. Public
Git commits expose those names and email addresses. Retain third-party credits
and licenses.

If a source ZIP is later prepared, verify that it contains no Git history,
local configuration or build products. A fresh public repository should have
only reviewed source files and newly initialized history. No bundle or ZIP is
currently supplied by this project.

After the source tree is reviewed and publication is authorized, configure the
intended destination remote and push the new branch. Do not push as part of
preparation. Supply corresponding source with any APK distribution, in keeping
with the GPL. The Gradle release build is non-debuggable and shrunk. A locally
installed build may use a development signing certificate for update
compatibility; it is not a Google-certified receiver or a Play Store production
signing setup. A public release needs its own private signing store and a
separate authorized AA identity/provisioning design. Neither key belongs in
this repository.

For release signing, set `PROJECTION_SIGNING_STORE` to an external PKCS12 store
(alias `projection`) and supply `PROJECTION_SIGNING_PASSWORD` in the build
environment. Never commit either value or the store. Signing an APK does not
provide AA client authentication. A fresh public installation has no accepted
client key; do not claim otherwise.

Do not add private runtime data to issues or release notes. Review screenshots
and diagnostics before posting them: Android Auto's live UI can show personal
locations, names, accounts and media content.
