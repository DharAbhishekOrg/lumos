/// Lumos - ios-app/shell / Design tokens (Midnight and Daylight).
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-UX-001, TRS-UX-002, SRS-UX-003
/// ADRs: -
/// Threading: any
/// GENERATED - DO NOT EDIT. Generator: tools/tokensgen
/// Source: docs/design/midnight-aurora.tokens.json (CR-008, accepted 2026-11-23)

/// The design tokens as plain values, so they compile and test on any host.
public enum LumosTokens {

    /// A colour's value in each theme, as 0xRRGGBBAA.
    public struct ThemedColor: Equatable, Sendable {
        /// The value in Midnight, the dark default theme.
        public let midnight: UInt32
        /// The value in Daylight, the light theme.
        public let daylight: UInt32
    }

    /// A text style. Sizes are points at the default Dynamic Type size.
    public struct TextStyle: Equatable, Sendable {
        /// True for the display face (Sora), false for the platform UI face.
        public let display: Bool
        /// The point size.
        public let size: Double
        /// The line height in points.
        public let lineHeight: Double
        /// The weight, 100 to 900.
        public let weight: Int
        /// The tracking in points.
        public let letterSpacing: Double
    }

    /// Colours.
    public enum Color {
        /// The page behind everything: app background, under the floating navigation layer.
        public static let canvas = ThemedColor(midnight: 0x0A0D1AFF, daylight: 0xF6F6FBFF)
        /// Cards, list rows, the chat background. Body text (ink, ink-muted, ink-subtle) passes AA on it.
        public static let surface = ThemedColor(midnight: 0x121629FF, daylight: 0xFFFFFFFF)
        /// Sheets, menus, the safety sheet, incoming chat bubbles' neighbourhood. In light it is white with a
        /// shadow.
        public static let surfaceRaised = ThemedColor(midnight: 0x1A1F38FF, daylight: 0xFFFFFFFF)
        /// Pressed rows, chips at rest, the composer field. Danger text passes on it (4.7:1 dark).
        public static let surfaceHigh = ThemedColor(midnight: 0x242A4AFF, daylight: 0xECEDF6FF)
        /// Hairlines between list rows. Decorative only: never the only boundary of a control.
        public static let divider = ThemedColor(midnight: 0x262C4CFF, daylight: 0xDADCEAFF)
        /// Borders of controls and inputs, focus rings' outer ring. 3:1 or more on canvas, surface and
        /// surface-high (WCAG 1.4.11).
        public static let outline = ThemedColor(midnight: 0x6E779EFF, daylight: 0x767C99FF)
        /// Primary text and icons.
        public static let ink = ThemedColor(midnight: 0xF3F4FAFF, daylight: 0x12152AFF)
        /// Secondary text: bios, prompt questions, timestamps beside a name.
        public static let inkMuted = ThemedColor(midnight: 0xB6BBD3FF, daylight: 0x454A66FF)
        /// Tertiary text: captions, placeholder text, sent and delivered ticks. Still 4.5:1 on canvas and
        /// surface.
        public static let inkSubtle = ThemedColor(midnight: 0x959BB8FF, daylight: 0x5E637FFF)
        /// THE brand colour: primary buttons, selected tab, links, the outgoing bubble's family. Text in
        /// violet passes on canvas and surface.
        public static let violet = ThemedColor(midnight: 0x9D86FFFF, daylight: 0x5A3BE0FF)
        /// Text and icons ON a violet fill (6.7:1 in both themes).
        public static let onViolet = ThemedColor(midnight: 0x0A0D1AFF, daylight: 0xFFFFFFFF)
        /// Tonal containers: selected chips, the visibility pill, prompt cards. Ink and violet both pass on
        /// it.
        public static let violetSoft = ThemedColor(midnight: 0x2A2360FF, daylight: 0xECE7FFFF)
        /// Nearby and live: someone is here now, the Bluetooth/Wi-Fi path, nearby-only media. Never used for
        /// danger or for brand CTAs.
        public static let aqua = ThemedColor(midnight: 0x3DDCC4FF, daylight: 0x00796BFF)
        /// Interest and the match moment: the 'Interested' action, the match celebration. Kept away from
        /// danger controls; always with an icon.
        public static let coral = ThemedColor(midnight: 0xFF8A7AFF, daylight: 0xC2412FFF)
        /// Venues and perks, and gentle caution (not danger): 'only visible to people here', expiring
        /// visibility.
        public static let amber = ThemedColor(midnight: 0xFFBE5CFF, daylight: 0x8A5A00FF)
        /// Block, report, emergency wipe, failed sends. Always paired with an icon and a word: never colour
        /// alone (SRS-NFR-A11Y-005).
        public static let danger = ThemedColor(midnight: 0xFF5F72FF, daylight: 0xC21F35FF)
        /// The owner's ruling: two blue ticks when read. Only for the read state, on every bubble colour
        /// (4.7:1 dark, 4.4:1 light).
        public static let read = ThemedColor(midnight: 0x5AB4FFFF, daylight: 0x0A66C2FF)
        /// Queued, sent and delivered ticks: the same glyph family, told apart by shape and by their spoken
        /// labels, not by colour.
        public static let tick = ThemedColor(midnight: 0x959BB8FF, daylight: 0x5E637FFF)
        /// Messages I sent. Ink and every tick colour pass on it.
        public static let bubbleOut = ThemedColor(midnight: 0x3B2F8FFF, daylight: 0xE4DDFFFF)
        /// Messages I received.
        public static let bubbleIn = ThemedColor(midnight: 0x1A1F38FF, daylight: 0xECEDF6FF)
        /// First stop of the aurora: used ONLY for the match moment's glow and the brand mark. Never a
        /// full-screen wash, never behind text.
        public static let auroraStart = ThemedColor(midnight: 0x9D86FFFF, daylight: 0x5A3BE0FF)
        /// Second stop of the aurora; same restrictions.
        public static let auroraEnd = ThemedColor(midnight: 0x3DDCC4FF, daylight: 0x00796BFF)
        /// Behind sheets and dialogs. With Reduce Transparency it becomes opaque canvas.
        public static let scrim = ThemedColor(midnight: 0x05070FA3, daylight: 0x12152A66)
    }

    /// Spacing, in points.
    public enum Space {
        /// Icon-to-label gap, tick spacing.
        public static let space1: Double = 4
        /// Between related items: chips, bubble groups from one sender.
        public static let space2: Double = 8
        /// Inside compact controls; between list row text lines.
        public static let space3: Double = 12
        /// Screen edge margin on phones; card padding.
        public static let space4: Double = 16
        /// Between sections; sheet padding; tablet edge margin.
        public static let space6: Double = 24
        /// Above a screen's primary action; around the match moment.
        public static let space8: Double = 32
        /// Minimum touch target on Android (48dp); iOS uses 44pt.
        public static let space12: Double = 48
    }

    /// Corner radii, in points.
    public enum Radius {
        /// Chips, small buttons, the composer's send button container.
        public static let sm: Double = 10
        /// Cards, photo tiles, list groups.
        public static let md: Double = 16
        /// Chat bubbles (with a 6px tail corner), sheets' top corners.
        public static let lg: Double = 22
        /// Profile hero photo, the match card.
        public static let xl: Double = 32
        /// Primary buttons, the visibility pill, the floating tab bar.
        public static let pill: Double = 999
    }

    /// Text styles.
    public enum Text {
        /// Screen titles at the top of a scroll view (iOS Large Title size). One per screen.
        public static let display = TextStyle(
            display: true, size: 34, lineHeight: 41, weight: 700, letterSpacing: -0.4)
        /// Moments: the match screen, onboarding steps, empty states.
        public static let title1 = TextStyle(
            display: true, size: 28, lineHeight: 34, weight: 600, letterSpacing: -0.2)
        /// A person's name on their profile; section heads.
        public static let title2 = TextStyle(
            display: false, size: 22, lineHeight: 28, weight: 600, letterSpacing: 0)
        /// Row titles, chat header name, button labels.
        public static let headline = TextStyle(
            display: false, size: 17, lineHeight: 22, weight: 600, letterSpacing: 0)
        /// Chat messages, bios, prompt answers. Android maps to Body Large 16/24.
        public static let body = TextStyle(
            display: false, size: 17, lineHeight: 24, weight: 400, letterSpacing: 0)
        /// Card subtitles, prompt questions.
        public static let callout = TextStyle(
            display: false, size: 15, lineHeight: 20, weight: 400, letterSpacing: 0)
        /// Metadata under a row; timestamps in chat.
        public static let footnote = TextStyle(
            display: false, size: 13, lineHeight: 18, weight: 400, letterSpacing: 0)
        /// Smallest allowed text: badges and labels. Nothing in the app is smaller.
        public static let caption = TextStyle(
            display: false, size: 12, lineHeight: 16, weight: 500, letterSpacing: 0.2)
    }
}
