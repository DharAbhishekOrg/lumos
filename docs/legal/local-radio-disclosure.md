<!--
  Lumos - docs/legal / Local-radio disclosure, shipped with the application.
  Author: dhar.abhishek.email@gmail.com
  Implements: SRS-ONB-002, SRS-NFR-COM-003
  ADRs: ADR-002
  Threading: n/a
  Notes: Shipped and displayed. SRS-ONB-002 names this as a document in its own
  right rather than a paragraph of the Privacy Notice, and that is the right
  call: a person deciding whether to let an app use their phone's radio to
  announce their presence deserves to read about that on its own. The behaviour
  described here is ADR-002 and is built in WP-03; the disclosure is written now
  because the consent for it is taken now.
-->

# How Lumos uses your phone's radio

**Document version: 2026-08-19.1**

## What this is about

Lumos finds people near you using Bluetooth Low Energy - the same short-range
radio your headphones use. This page explains what your phone transmits, what
it listens for, and what that means for you. It is separate from the Privacy
Notice because it is the part people most deserve to decide about knowingly.

## What your phone sends out

When you turn on Visible Nearby, your phone broadcasts a short, rotating code.
It is not your name, your profile, your photo or a fixed device identifier. It
changes on a schedule, so somebody logging broadcasts in one place cannot
recognise the same phone in another place tomorrow.

**Your phone does not broadcast anything until you turn Visible Nearby on.**
Installing Lumos does not turn it on. Updating Lumos does not turn it on.
Restarting your phone does not turn it on. Granting the Bluetooth permission
does not turn it on. It is off until you switch it on, and it stays off.

## What your phone listens for

Other phones running Lumos, when you are looking. Your phone works out roughly
how close another phone is from how strong its signal is - "very close",
"nearby", "in the area". It does not work out a direction and it does not work
out a distance in metres, because the signal is not good enough to know that
and pretending otherwise would be a lie you might act on.

## What this does not do

It does not use GPS. It does not know your address, your street or your city.
It does not send your position to us or to anyone else, because it never
computes one.

## What Bluetooth can reveal anyway

Being honest about the limits: anyone with a radio receiver can hear that
*some* Bluetooth device is transmitting nearby. That is true of every Bluetooth
device you own. What the rotating code prevents is that listener tying those
transmissions to you, or to each other over time. It does not make your phone
invisible, and no app can.

Turning Visible Nearby off stops the broadcast entirely. That is the control
that actually works, and it is one tap.

## Battery and permissions

Lumos asks for Bluetooth permission when you first use nearby discovery, not
when you install it. If you never use nearby discovery, you are never asked.

Scanning uses battery. Lumos scans harder when you are actively looking and
backs off when you are not.

## Where this document stands

The behaviour described here is the design in ADR-002 and is delivered in a
later release; this disclosure exists now because your consent to it is taken
now, at onboarding, rather than sprung on you later. Radio behaviour on real
hardware is verified by the owner on physical devices before release, and the
legal review of this text is owner-executed under SRS-NFR-COM-009 (gate G-09).
