# Environments

Cloud and push identifiers for each Lumos environment. **Identifiers only.**
Keys, service-account files, `google-services.json`, APNs `.p8` keys and
credentials never appear in this repository (TRS-REP-005); they live in the
owning cloud project's Secret Manager or in CI secrets.

Everything below was created by or with the owner on the owner's personal
Google account. Changing any of it is owner-executed.

## Staging

| Item | Value |
| --- | --- |
| Google Cloud project | `lumos-staging-515413` (name `lumos-staging`, no organisation) |
| Project number | `540361691999` |
| Billing | the owner's "My Billing Account 1"; INR |
| Budget alert | "lumos-staging monthly", ₹2,000/month, scoped to this project only, email at 50 / 90 / 100 %. Alerts only: it stops nothing. (A stale $25 alert left on the Maps-only billing account was deleted on 2026-11-22.) |
| Firebase | Added to the same project, pay-as-you-go (Blaze) plan because billing is on. **Google Analytics off.** Only FCM is intended to be used, and FCM has no charge |
| Android app | `com.lumos.app.staging` ("Lumos staging"), Firebase App ID `1:540361691999:android:c93f0a44791732f2375cea` |
| iOS app | Not yet: the owner's Apple Developer Program enrolment was submitted and paid on 2026-11-22 and is pending Apple's review |
| APNs key | Not yet. When created: Sandbox, Team Scoped, stored as secret `apns-sandbox-key` in this project's Secret Manager |
| Region | `asia-south1` (Mumbai), owner ruling 2026-11-22 |
| Database | Not yet created (WP-05 S8). PostgreSQL; its URL reaches `lumosd` as `LUMOS_DATABASE_URL` from Secret Manager |
| Redis | Not yet created (WP-05 S8). Must use the **`noeviction`** policy: eviction would silently drop pending messages. Its URL reaches `lumosd` as `LUMOS_REDIS_URL` from Secret Manager |

The Android package carries no `.debug` suffix: `buildTypes.debug` in
`android-app/app/build.gradle.kts` sets none, so debug and release staging
builds share `com.lumos.app.staging`.

### Push configuration (WP-05 S6)

`lumosd` wakes phones only when it is told how. Everything below is
**owner-executed**; the code is ready and tested against fake provider servers.

| Variable | Value for staging | What it does |
| --- | --- | --- |
| `LUMOS_FCM_PROJECT` | `lumos-staging-515413` | Turns on Android wakes. FCM is called as the Cloud Run service's own service account, so no key file exists |
| `LUMOS_APNS_KEY_FILE` | path where the secret `apns-sandbox-key` is mounted | The `.p8` key, mounted from Secret Manager as a file |
| `LUMOS_APNS_KEY_ID` | the Key ID Apple shows for the key | |
| `LUMOS_APNS_TEAM_ID` | the owner's Apple Team ID | |
| `LUMOS_APNS_TOPIC` | `com.lumos.app` (the iOS bundle id) | |
| `LUMOS_APNS_PRODUCTION` | unset (sandbox) | `true` only in production |

Owner steps when staging is deployed (WP-05 S8):
1. In `lumos-staging-515413`, enable the **Firebase Cloud Messaging API**
   (`fcm.googleapis.com`).
2. Grant the Cloud Run service account the role
   **Firebase Cloud Messaging API Admin** (`roles/firebasecloudmessaging.admin`)
   on that project, and nothing broader.
3. After Apple approves the enrolment: create the APNs key, upload it as the
   secret `apns-sandbox-key`, and mount it into the service as a file. Then
   set the four `LUMOS_APNS_*` variables.

The APNs variables are all-or-none: setting some of them stops `lumosd` at
startup rather than running half-configured.

## Development

| Item | Value |
| --- | --- |
| Firebase project | `lumos-64cd3` ("Lumos"), billing disabled |
| Android app | Registered as `com.lumos.app.dev.debug`, which **does not match** the dev build's `com.lumos.app.dev`. To be corrected when the dev build is wired for push |

## QA and production

Not created. Production gets its own project, Firebase app and APNs
Production key before launch, so a leaked test key can never reach real users.

## Recorded

2026-11-21, WP-05 (push and staging entry criteria, `docs/plans/wp05-plan.md`).
