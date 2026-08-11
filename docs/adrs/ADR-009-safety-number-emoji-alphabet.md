# ADR-009 — The frozen 64-emoji safety-number alphabet

- **Status:** **ACCEPTED** as the S4b freeze, implementing the owner's carried-forward security criterion. Selection rationale below; individual glyph swaps within the criteria are a change request under TRS-CONST-003.
- **Decided:** 2026-08-11, WP-01 slice S4b
- **Affected requirements:** SRS-IDN-005, SRS-MAT-008, TRS-CRY-014
- **Affected artefacts:** `protocol/constants/lumos_constants.json` (`SAFETY_NUMBER_EMOJI_ALPHABET`), `crypto/safety_number.json`, both apps' verification UI

## Why the alphabet is a security decision

ADR-005 fixed the safety number at 60 digits + 6 emoji from a 64-glyph alphabet.
The emoji code is a surface **two people compare by eye** to detect a
machine-in-the-middle: if the attacker can make two different key pairs render a
code that *looks* the same, the check fails silently. The owner carried a
binding criterion forward from S3 — **cross-platform font survival** and
**non-confusability** — and this ADR applies it.

## Selection criteria (binding)

1. **Single Unicode codepoint only.** No ZWJ sequences, no variation selectors,
   no flags, no keycaps, no skin-tone or gender modifiers. Multi-codepoint emoji
   fragment differently across platforms — a family emoji may render as one
   glyph or as several. `TestAlphabetMeetsADR009MechanicalCriteria` enforces
   this mechanically (exactly 64, all unique, each one rune, no modifier/joiner).
2. **Default emoji presentation**, so no glyph depends on a variation selector to
   avoid rendering as monochrome text.
3. **Distinct silhouettes across categories.** The 64 are spread over animals,
   food, nature, objects and symbols so no two are a colour-only or
   same-family variation (no "red car / blue car", no two similar faces). This
   is the judgement half of the criterion and cannot be unit-tested; it is
   recorded here for review and future change control.
4. **Broadly recognisable and nameable** by a non-developer, since the printed
   test card (TRS-TST-014) refers to them by name.

## Entropy

64 glyphs = exactly 6 bits each; 6 emoji carry 36 bits, alongside the 60 decimal
digits. A 64-entry alphabet keeps the digit→glyph mapping an exact bit-slice
with no modulo bias for the byte-derived indices.

## The frozen alphabet

Index is the position used by the 6-bit encoding. Order is itself frozen; the
constants hash covers it.

| # | Glyph | Name |
| --- | --- | --- |
| 0 | 🐶 | dog |
| 1 | 🐱 | cat |
| 2 | 🐴 | horse |
| 3 | 🐮 | cow |
| 4 | 🐷 | pig |
| 5 | 🐸 | frog |
| 6 | 🐵 | monkey |
| 7 | 🐔 | chicken |
| 8 | 🐧 | penguin |
| 9 | 🐢 | turtle |
| 10 | 🐝 | bee |
| 11 | 🦋 | butterfly |
| 12 | 🐙 | octopus |
| 13 | 🐬 | dolphin |
| 14 | 🐘 | elephant |
| 15 | 🐊 | crocodile |
| 16 | 🍎 | apple |
| 17 | 🍌 | banana |
| 18 | 🍇 | grapes |
| 19 | 🍓 | strawberry |
| 20 | 🍉 | watermelon |
| 21 | 🍍 | pineapple |
| 22 | 🌽 | corn |
| 23 | 🍄 | mushroom |
| 24 | 🍞 | bread |
| 25 | 🍕 | pizza |
| 26 | 🎂 | cake |
| 27 | 🍩 | doughnut |
| 28 | ☕ | coffee |
| 29 | 🍦 | ice-cream |
| 30 | 🌵 | cactus |
| 31 | 🌲 | evergreen |
| 32 | 🌻 | sunflower |
| 33 | 🌹 | rose |
| 34 | 🍁 | maple-leaf |
| 35 | ⭐ | star |
| 36 | 🌈 | rainbow |
| 37 | 🔥 | fire |
| 38 | 💧 | droplet |
| 39 | ⚡ | lightning |
| 40 | 🔑 | key |
| 41 | 🔒 | lock |
| 42 | 🎈 | balloon |
| 43 | 🎁 | gift |
| 44 | ⚽ | soccer-ball |
| 45 | 🎸 | guitar |
| 46 | 🎺 | trumpet |
| 47 | 🎯 | dartboard |
| 48 | 🚀 | rocket |
| 49 | ⚓ | anchor |
| 50 | 🚲 | bicycle |
| 51 | 🚂 | locomotive |
| 52 | ⏰ | alarm-clock |
| 53 | 💡 | light-bulb |
| 54 | 🔔 | bell |
| 55 | 🎧 | headphones |
| 56 | 📷 | camera |
| 57 | 🔭 | telescope |
| 58 | 🎨 | artist-palette |
| 59 | 🎪 | circus-tent |
| 60 | 💙 | blue-heart |
| 61 | 🎵 | musical-note |
| 62 | 🏆 | trophy |
| 63 | 👑 | crown |

## Consequences

- Changing any glyph, or the order, moves `constants_hash` and regenerates
  `crypto/safety_number.json` (TRS-CONST-003).
- The external cryptographic review (gate G-07) should sanity-check the set for
  any confusable pair a native emoji-font designer might catch that this
  criteria-driven selection did not.
