<!--
  Lumos - docs/legal / Privacy Notice, shipped with the application.
  Author: dhar.abhishek.email@gmail.com
  Implements: SRS-ONB-002, SRS-NFR-PRI-001
  ADRs: ADR-003
  Threading: n/a
  Notes: Shipped and displayed, not documentation. Every claim here is one the
  code can be checked against - the data map (docs/privacy/data-map.md) is the
  engineering-side version of this same table, and the two are meant to be read
  together. Do not add a claim here that no test backs.
-->

# Privacy Notice

**Document version: 2026-08-19.1**

## The short version

Your profile, your messages, your photos and your voice notes stay on your
phone. When you talk to someone, the messages go to them, encrypted, and we
cannot read them. We are not able to hand over what we do not have.

## What stays on your phone

All of it, in an encrypted store only your device can open:

- your profile - your alias, age, pronouns, gender identity, orientation,
  relationship intent, interests, bio and photos
- who you have blocked
- who you have matched with, and your conversations
- your settings, including whether you are visible to people nearby
- the fact that you passed the age gate, and which version of each document you
  accepted

## What leaves your phone

Your messages, when you send them - encrypted end to end, to the person you
sent them to. Nobody in the middle can read them, including us.

Nothing else, in this version of the app. No profile, no photo, no voice note
and no message content is uploaded anywhere.

## What we never collect

We do not collect your exact location. Lumos uses Bluetooth to notice that
another phone running Lumos is *near* you; it does not calculate where either
of you is, and it does not send a position anywhere.

We do not use advertising identifiers. The permission is not in the app.

We do not put your messages, your photos, your orientation, your gender
identity or your social graph into analytics, logs or crash reports. Not
truncated, not hashed, not "anonymised" - they are not there.

## Your age and your consent

To use Lumos you confirm your date of birth. Lumos checks it against the
minimum age and then **discards it**. What is stored is the fact that you
passed, the date you passed, and the minimum you were checked against. Your
date of birth is not written to your phone and is not sent anywhere.

## Your photos

When you add a photo, Lumos strips the hidden data cameras attach to image
files - the GPS coordinates, the camera make and model, the timestamp - before
the photo is stored. That happens on your device, when you add it, not when you
send it.

## What you can do

You can see exactly what a nearby person or a match would see, from inside the
app, computed the same way the app computes it for them.

You can change any field's visibility, delete your profile, reset your identity
or wipe everything. Wipe destroys the keys first, which means whatever is left
on the storage chip afterwards is unreadable even to us and even to someone
with the device in their hand.

## Backups

Your Lumos data is excluded from your phone's automatic cloud backup. Your
keys, your messages and your profile are not in your iCloud or Google backup,
by design. If you want a copy of your content, the app's recovery setup makes
one you control, protected by a passphrase you choose.

## Where this document stands

This text describes what the software actually does today. It has **not yet had
the jurisdiction-specific legal review** that SRS-NFR-COM-009 requires before
release - that is owner-executed and tracked as gate G-09 - and it does not yet
name a data controller or a supervisory authority, because those are decisions
of that review. The engineering record behind every statement above is
`docs/privacy/data-map.md`.
