# Runtime security controls

## Goal

Keep one shared Phonoteka interface and avoid producing many nearly identical lab APKs. The existing `vulnerable` and `hardened` flavors remain useful as controlled baselines; runtime controls let the vulnerable **debug** build enable individual protections without rebuilding.

## Runtime controls implemented

| Control | Real decision point | Demonstrable result |
|---|---|---|
| OTP/recovery single-use | `SecurityDecisions.otpSingleUse()` → `TotpAuthenticatorManager` and `LocalAuthManager.recover()` | With protection off, the mock fixture permits reuse in the demonstration. With it on, an already accepted TOTP time-step or recovery code is rejected. |
| QR ticket single-use | `SecurityDecisions.qrTicketSingleUse()` → `MockInviteAuthority.scan()` | A ticket can be scanned again while disabled; with protection on, the second scan fails as replay. |
| Step-up confirmation | `SecurityDecisions.requireStepUp()` → `PhonotekaApp.requireAuthority()` and existing biometric/PIN gates | Sensitive actions are allowed directly while disabled; while enabled they require the configured step-up gate. |
| Fresh library authentication | `SecurityDecisions.requireFreshAuthForLibrary()` → `PhonotekaApp.requireAuthority()` | A protected library operation is rejected if the local authentication is absent or older than five minutes. |

The controls live in `SecurityProfile`, persist under app-private SharedPreferences, update a `StateFlow`, and are shown in the “Режим безопасности” screen. A successful change is written to the unified audit log without OTPs, PINs, recovery codes, or tokens.

## Fixed at build time

TLS trust anchors, Android Network Security Config, `usesCleartextTraffic`, and `allowBackup` are package/manifest policy. They are shown as read-only build facts rather than misleading runtime switches. The vulnerable and hardened variants still provide the baseline comparison for those controls.

## Safety boundaries

- Only the vulnerable **debug** flavor exposes editable switches for comparing the local fixture.
- Hardened builds keep their protections enabled; Release builds do not expose editable controls.
- The fixtures and credentials are local educational data. This feature does not claim to intercept real Wi-Fi traffic or protect a real service.
- OTP single-use prevents a second use after a successful first use. It does not prevent an attacker who steals an unused OTP from racing the legitimate user; preventing that requires a server challenge/proof-of-possession protocol, not a client checkbox.

## Verification

The current local source passes both Debug unit-test suites (327 tests per variant, zero failures), and both Debug APKs assemble. Both variants have distinct launcher labels: “Фонотека — уязвимая” and “Фонотека — защищённая”. Device acceptance and release publication must be verified against the exact commit being distributed.
