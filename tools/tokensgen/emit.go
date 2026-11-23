// Lumos - tools/tokensgen / Android resources and Swift constants from the tokens.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-UX-001, TRS-UX-002, SRS-UX-003
// ADRs: -
// Threading: any
// Notes: Android reads Daylight from values/ and Midnight from values-night/,
// so the Material 3 DayNight theme picks the right set and the app chooses
// which with its night mode (Midnight by default, CR-008). iOS gets plain
// values, platform-free, because the shell never imports SwiftUI or UIKit; the
// SwiftUI layer turns them into Colors when the app target exists. Output is
// byte-deterministic: tokens keep their file order and numbers print in one
// fixed form, so -check can compare bytes.
package main

import (
	"fmt"
	"strconv"
	"strings"
)

// Generated file paths, relative to the repository root.
const (
	AndroidDayPath   = "android-app/app/src/main/res/values/lumos_tokens.xml"
	AndroidNightPath = "android-app/app/src/main/res/values-night/lumos_tokens.xml"
	SwiftPath        = "ios-app/shell/Sources/LumosAppShell/LumosTokens.swift"
)

// Emit renders every generated file.
func Emit(t *Tokens) map[string][]byte {
	return map[string][]byte{
		AndroidDayPath:   androidXML(t, false),
		AndroidNightPath: androidXML(t, true),
		SwiftPath:        swift(t),
	}
}

func resName(token string) string { return "lumos_" + strings.ReplaceAll(token, "-", "_") }

func argb(c RGBA) string { return fmt.Sprintf("#%02X%02X%02X%02X", c.A, c.R, c.G, c.B) }

func num(v float64) string { return strconv.FormatFloat(v, 'f', -1, 64) }

func xmlEscape(s string) string {
	return strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", "--", "- -").Replace(s)
}

func androidXML(t *Tokens, night bool) []byte {
	theme, qualifier := "Daylight", "values"
	if night {
		theme, qualifier = "Midnight", "values-night"
	}
	var b strings.Builder
	b.WriteString(`<?xml version="1.0" encoding="utf-8"?>
<!--
  Lumos - android-app/app / Design tokens, ` + theme + ` theme (` + qualifier + `).
  Author: dhar.abhishek.email@gmail.com
  Implements: TRS-UX-001, TRS-UX-002, SRS-UX-003
  ADRs: -
  Threading: n/a
  GENERATED - DO NOT EDIT. Generator: tools/tokensgen
  Source: ` + SourcePath + ` (CR-008, accepted 2026-11-23)
-->
<resources>
`)
	for _, c := range t.Colors {
		v := c.Daylight
		if night {
			v = c.Midnight
		}
		fmt.Fprintf(&b, "    <!-- %s -->\n    <color name=\"%s\">%s</color>\n", xmlEscape(c.Usage), resName(c.Name), argb(v))
	}
	if !night {
		for _, l := range append(append([]Length{}, t.Spacing...), t.Radius...) {
			fmt.Fprintf(&b, "    <dimen name=\"%s\">%sdp</dimen>\n", resName(l.Name), num(l.Value))
		}
		for _, s := range t.Type {
			n := resName("type-" + s.Name)
			fmt.Fprintf(&b, "    <dimen name=\"%s_size\">%ssp</dimen>\n", n, num(s.Size))
			fmt.Fprintf(&b, "    <dimen name=\"%s_line_height\">%ssp</dimen>\n", n, num(s.LineHeight))
			fmt.Fprintf(&b, "    <integer name=\"%s_weight\">%d</integer>\n", n, s.Weight)
		}
	}
	b.WriteString("</resources>\n")
	return []byte(b.String())
}

// camel turns "surface-raised" into "surfaceRaised" and "space-1" into "space1".
func camel(s string) string {
	parts := strings.Split(s, "-")
	for i := 1; i < len(parts); i++ {
		parts[i] = strings.ToUpper(parts[i][:1]) + parts[i][1:]
	}
	return strings.Join(parts, "")
}

// doc wraps a usage note into Swift doc-comment lines at the given indent.
func doc(text, indent string) string {
	const width = 110
	var b strings.Builder
	line := ""
	for _, w := range strings.Fields(text) {
		if line != "" && len(indent)+4+len(line)+1+len(w) > width {
			b.WriteString(indent + "/// " + line + "\n")
			line = w
			continue
		}
		if line == "" {
			line = w
		} else {
			line += " " + w
		}
	}
	b.WriteString(indent + "/// " + line + "\n")
	return b.String()
}

func rgbaHex(c RGBA) string { return fmt.Sprintf("0x%02X%02X%02X%02X", c.R, c.G, c.B, c.A) }

func swift(t *Tokens) []byte {
	var b strings.Builder
	b.WriteString(`/// Lumos - ios-app/shell / Design tokens (Midnight and Daylight).
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-UX-001, TRS-UX-002, SRS-UX-003
/// ADRs: -
/// Threading: any
/// GENERATED - DO NOT EDIT. Generator: tools/tokensgen
/// Source: ` + SourcePath + ` (CR-008, accepted 2026-11-23)

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
`)
	for _, c := range t.Colors {
		fmt.Fprintf(&b, "%s        public static let %s = ThemedColor(midnight: %s, daylight: %s)\n",
			doc(c.Usage, "        "), camel(c.Name), rgbaHex(c.Midnight), rgbaHex(c.Daylight))
	}
	b.WriteString("    }\n\n    /// Spacing, in points.\n    public enum Space {\n")
	for _, l := range t.Spacing {
		fmt.Fprintf(&b, "%s        public static let %s: Double = %s\n", doc(l.Usage, "        "), camel(l.Name), num(l.Value))
	}
	b.WriteString("    }\n\n    /// Corner radii, in points.\n    public enum Radius {\n")
	for _, l := range t.Radius {
		fmt.Fprintf(&b, "%s        public static let %s: Double = %s\n", doc(l.Usage, "        "), camel(strings.TrimPrefix(l.Name, "radius-")), num(l.Value))
	}
	b.WriteString("    }\n\n    /// Text styles.\n    public enum Text {\n")
	for _, s := range t.Type {
		fmt.Fprintf(&b, "%s        public static let %s = TextStyle(\n            display: %t, size: %s, lineHeight: %s, weight: %d, letterSpacing: %s)\n",
			doc(s.Usage, "        "), camel(s.Name), s.Family == "display", num(s.Size), num(s.LineHeight), s.Weight, num(s.LetterSpacing))
	}
	b.WriteString("    }\n}\n")
	return []byte(b.String())
}
