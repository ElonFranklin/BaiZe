# Open-Source / Internal Beta Scope (v0.9.0-launch)

**Date:** 2026-07-21  
**Mode:** GitHub open source + local / self-provided model beta  
**Not included:** public registration, SMS, recharge, withdrawals, paid marketplace

## Intended use

- Android client as a **soul companion container**
- Users bring their own cloud API keys (clipboard import/export)
- Local model path exists but is **not productized** for this release (default cloud-only UX)
- Persona import/export and chat features for personal use

## Explicitly disabled / not production-ready

| Area | Status |
|---|---|
| Cloud account register / login UI paths | Hidden or hard-fail for beta |
| SMS public auth | Server flag `FEATURE_PUBLIC_AUTH_ENABLED` default **off** |
| Gem recharge / payments | Server flag `FEATURE_PAYMENTS_ENABLED` default **off**; client refuses fake success |
| Developer withdraw | Same payments flag, default **off** |
| Paid content download URLs | Redacted (`contentUrl: null`) in demo responses |
| Official hosted cloud | **None** — self-host `server/` only as example |

## Server feature flags

```bash
# DO NOT enable in production without a full security review
FEATURE_PUBLIC_AUTH_ENABLED=false   # default
FEATURE_PAYMENTS_ENABLED=false      # default
```

Known issues if flags are forced on (see security review):

- Refresh token storage/lookup mismatch
- SMS attempt counter bug
- Withdraw double-spend risk
- Payment callback not a complete money loop

Full review: `docs/SECURITY-REVIEW-2026-07-21-prelaunch.md`

## Client release gates (checklist)

- [x] Hide account / income / shop from settings hub menu
- [x] Hide shop button on legacy settings if present
- [x] Hide test-account UI on auth screen
- [x] Mock login/register/recharge hard-fail with beta message
- [x] No fake local gem grant on recharge
- [x] `local.properties` untracked; keystore patterns in `.gitignore`
- [x] Release APK rebuild + device smoke (2026-07-22)
- [x] README current limits + build steps (2026-07-22)
- [x] GitHub `origin` + tag `v0.9.0-launch` (https://github.com/ElonFranklin/BaiZe)

## What to test on device

1. Cold start → default persona 白泽
2. Configure cloud model via clipboard → one chat round-trip
3. Switch 暖暖 / 无名
4. Voice input fills input box (no auto-send)
5. Import/export persona & chat if used
6. Confirm no account/shop/recharge entry in normal UX

## Security posture for this release

Open source is for **code transparency and local beta**, not for operating a money-bearing service.  
Do not deploy `server/` with real payments or public SMS until P0 items in the security review are fixed.

## Clipboard config caveat (2026-07-22)

- Clipboard **export** intentionally omits API Key (template only: baseUrl / model / provider).
- After import, user must **paste a real API Key separately**, then save.
- Sample / placeholder keys (e.g. sk-xxxxxxxx) are blocked on save.
- If you only “fill the template and re-import” without replacing the key, cloud chat will fail with **403 Authorization failed**.
