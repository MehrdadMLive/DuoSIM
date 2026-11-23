# DuoSIM — Samsung validation track

DuoSIM is the Samsung-focused fork of [SimBridge](https://github.com/ttabouch12/simbridge).
It preserves the upstream architecture and license notices while adding a dedicated validation
track for this two-device setup:

- **SIM Gateway:** Samsung Galaxy S21 Plus, model SM-G996B, Android 15 / One UI 7.
- **Main Phone:** Samsung Galaxy S26 Ultra, Android 16 / One UI 8.5.
- **Connection target:** same local Wi-Fi network or the S21 Plus hotspot; no cloud relay.

## Current implementation baseline

This fork starts from the upstream 2.8.0 codebase. The major modules are intentionally retained:

| Module | Responsibility |
|---|---|
| `:app` | SIM Gateway app installed on the S21 Plus |
| `:phone` | Main Phone app installed on the S26 Ultra |
| `:sip`, `:voice-api`, `:gateway` | SIP signalling and voice/media bridge |
| `:telephony` | Android cellular call integration |
| `:privileged` | Shizuku-backed privileged operations |

Package/application IDs are not being renamed in this initial engineering baseline. Changing them
would affect app identity, permissions, pairing, upgrades, and telecom registration. DuoSIM branding
and any eventual application-ID migration must be handled as a separately tested change.

## Compatibility facts vs. open questions

- The Gateway module declares Android API 30 as its minimum; Android 15 meets that requirement.
- The Main Phone module declares Android API 34 as its minimum; Android 16 meets that requirement.
- These SDK requirements do **not** prove Samsung call-audio compatibility.
- Upstream's published compatibility table does not currently list the SM-G996B.
- The first device-specific acceptance test is bidirectional cellular call audio on the S21 Plus.
  If call setup works but audio does not, test both supported Cellular audio modes and capture the
  in-app diagnostics before changing the telephony implementation.

## Device acceptance checklist

1. Install Gateway on the S21 Plus and complete Shizuku setup after reboot.
2. Pair the S26 Ultra using the in-app QR pairing flow over the same local network.
3. Verify the connection remains local and both devices reconnect after Wi-Fi/hotspot cycling.
4. Verify incoming cellular call notification and answer from the S26 Ultra.
5. Verify two-way speech: S26 microphone to remote caller, and remote caller to S26 speaker/earpiece.
6. Verify outgoing call from the S26 Ultra uses the S21 Plus SIM.
7. Verify SMS receive and send in both directions.
8. Record Android build numbers, carrier, audio mode, and diagnostic output for every failure.

## Build

Use JDK 17 and the Android SDK configured by Android Studio, then run:

```bash
./gradlew :app:assembleDebug :phone:assembleFullDebug :phone:assembleFossDebug
./gradlew :sip:test :gateway:test :telephony:test :privileged:test :voice-api:test
```

Debug APKs are generated under each module's `build/outputs/apk/` directory. The CI workflow
`.github/workflows/duosim-android.yml` builds and uploads these APKs as workflow artifacts.

## Upstream and licensing

This project is a fork of SimBridge. Preserve upstream attribution, `LICENSE`,
`THIRD_PARTY_NOTICES.md`, and applicable GPL-2.0-or-later source-distribution obligations.
