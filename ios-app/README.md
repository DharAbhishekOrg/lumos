# /ios-app

Native Swift consumer app (TRS Sec. 4.1, Sec. 6).

Swift 6 strict concurrency; SwiftUI app shell.

## Status

Created in WP-01 slice S1. **No application code exists yet.** The empty-shell
build is delivered in WP-01 slice S7; the SwiftPM `LumosProtocolConformance`
target (XCTest, macOS-runnable, **no UIKit**, no simulator) is delivered in
slice S5. Feature modules land from WP-02 onward per EDP Sec. 7.

## Rules

- Consumes generated constants from `/protocol/constants` only (TRS-REP-002,
  TRS-CONST-001). Hand-written constants are a defect.
- Per ADR-002, iOS advertises the Lumos service UUID only. Service-data
  advertising is never attempted on iOS.
