// Lumos - tools/tokensgen / The design-token source, read and resolved.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-UX-001, TRS-UX-002, SRS-UX-003
// ADRs: -
// Threading: any
// Notes: The token file is the CR-008 design system's own tokens.json, copied
// into docs/design/ when the owner accepted the CR. Its shape is the design
// system's: every colour is one entry with a value per theme ("dark" is
// Midnight, "light" is Daylight), a value is a hex colour, an rgba() with plain
// numbers, or "{other-token}". Anything else is an error, not a guess: a token
// that silently fell back to black would ship unreadable text.
package main

import (
	"encoding/json"
	"fmt"
	"math"
	"os"
	"regexp"
	"strconv"
	"strings"
)

// SourcePath is the token source, relative to the repository root.
const SourcePath = "docs/design/midnight-aurora.tokens.json"

// The CSS font-weight range a type style must sit in.
// lumoslint:allow-literal - CSS's weight scale, not a protocol value.
const minFontWeight = 100

// lumoslint:allow-literal - CSS's weight scale, not a protocol value.
const maxFontWeight = 900

// Theme ids in the token file.
const (
	themeMidnight = "dark"
	themeDaylight = "light"
)

// RGBA is a colour with 8-bit channels.
type RGBA struct{ R, G, B, A uint8 }

// Color is one resolved colour token.
type Color struct {
	Name     string
	Usage    string
	Midnight RGBA
	Daylight RGBA
}

// Length is one spacing or radius token, in points.
type Length struct {
	Name  string
	Value float64
	Usage string
}

// TextStyle is one type style.
type TextStyle struct {
	Name          string
	Family        string // "display" or "ui"
	Size          float64
	LineHeight    float64
	Weight        int
	LetterSpacing float64
	Usage         string
}

// Tokens is the whole resolved set.
type Tokens struct {
	Colors  []Color
	Spacing []Length
	Radius  []Length
	Type    []TextStyle
}

type rawFile struct {
	Color struct {
		Tokens []struct {
			Name  string            `json:"name"`
			Value map[string]string `json:"value"`
			Usage string            `json:"usage"`
		} `json:"tokens"`
	} `json:"color"`
	Type struct {
		Groups []struct {
			Family string `json:"family"`
			Styles []struct {
				Name          string  `json:"name"`
				FontSize      string  `json:"fontSize"`
				LineHeight    string  `json:"lineHeight"`
				FontWeight    int     `json:"fontWeight"`
				LetterSpacing string  `json:"letterSpacing"`
				Usage         string  `json:"usage"`
				Family        *string `json:"family"`
			} `json:"styles"`
		} `json:"groups"`
	} `json:"type"`
	Spacing rawLengths `json:"spacing"`
	Radius  rawLengths `json:"radius"`
}

type rawLengths struct {
	Tokens []struct {
		Name  string `json:"name"`
		Value string `json:"value"`
		Usage string `json:"usage"`
	} `json:"tokens"`
}

var (
	nameRe  = regexp.MustCompile(`^[a-z][a-z0-9]*(-[a-z0-9]+)*$`)
	hexRe   = regexp.MustCompile(`^#([0-9A-Fa-f]{6})([0-9A-Fa-f]{2})?$`)
	rgbaRe  = regexp.MustCompile(`^rgba\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(0|1|0?\.\d+)\s*\)$`)
	aliasRe = regexp.MustCompile(`^\{([a-z][a-z0-9-]*)\}$`)
)

// Load reads and resolves the token file.
func Load(path string) (*Tokens, error) {
	data, err := os.ReadFile(path) // #nosec G304 -- the repository's own token file
	if err != nil {
		return nil, err
	}
	return Parse(data)
}

// Parse resolves token-file bytes.
func Parse(data []byte) (*Tokens, error) {
	var raw rawFile
	dec := json.NewDecoder(strings.NewReader(string(data)))
	if err := dec.Decode(&raw); err != nil {
		return nil, fmt.Errorf("token file: %w", err)
	}
	t := &Tokens{}
	seen := map[string]bool{}
	var err error
	if t.Colors, err = colors(raw, seen); err != nil {
		return nil, err
	}
	if t.Spacing, err = lengths("spacing", raw.Spacing, seen); err != nil {
		return nil, err
	}
	if t.Radius, err = lengths("radius", raw.Radius, seen); err != nil {
		return nil, err
	}
	if t.Type, err = textStyles(raw); err != nil {
		return nil, err
	}
	if len(t.Colors) == 0 || len(t.Spacing) == 0 || len(t.Radius) == 0 || len(t.Type) == 0 {
		return nil, fmt.Errorf("token file is missing a family (colour, spacing, radius or type)")
	}
	return t, nil
}

func colors(raw rawFile, seen map[string]bool) ([]Color, error) {
	values := map[string]map[string]string{}
	for _, c := range raw.Color.Tokens {
		if !nameRe.MatchString(c.Name) || seen[c.Name] {
			return nil, fmt.Errorf("colour token %q: bad or duplicate name", c.Name)
		}
		seen[c.Name] = true
		values[c.Name] = c.Value
	}
	var out []Color
	for _, c := range raw.Color.Tokens {
		m, err := resolve(values, c.Name, themeMidnight, 0)
		if err != nil {
			return nil, err
		}
		d, err := resolve(values, c.Name, themeDaylight, 0)
		if err != nil {
			return nil, err
		}
		out = append(out, Color{Name: c.Name, Usage: c.Usage, Midnight: m, Daylight: d})
	}
	return out, nil
}

func textStyles(raw rawFile) ([]TextStyle, error) {
	var out []TextStyle
	for _, g := range raw.Type.Groups {
		for _, s := range g.Styles {
			fam := g.Family
			if s.Family != nil {
				fam = *s.Family
			}
			if fam != "display" && fam != "ui" {
				return nil, fmt.Errorf("type style %q: family %q is neither display nor ui", s.Name, fam)
			}
			size, err1 := px(s.FontSize)
			lh, err2 := px(s.LineHeight)
			ls := 0.0
			var err3 error
			if s.LetterSpacing != "" {
				ls, err3 = px(s.LetterSpacing)
			}
			if err1 != nil || err2 != nil || err3 != nil || !nameRe.MatchString(s.Name) || s.FontWeight < minFontWeight || s.FontWeight > maxFontWeight {
				return nil, fmt.Errorf("type style %q is malformed", s.Name)
			}
			out = append(out, TextStyle{Name: s.Name, Family: fam, Size: size, LineHeight: lh,
				Weight: s.FontWeight, LetterSpacing: ls, Usage: s.Usage})
		}
	}
	return out, nil
}

func lengths(family string, raw rawLengths, seen map[string]bool) ([]Length, error) {
	var out []Length
	for _, l := range raw.Tokens {
		if !nameRe.MatchString(l.Name) || seen[l.Name] {
			return nil, fmt.Errorf("%s token %q: bad or duplicate name", family, l.Name)
		}
		seen[l.Name] = true
		v, err := px(l.Value)
		if err != nil {
			return nil, fmt.Errorf("%s token %q: %w", family, l.Name, err)
		}
		out = append(out, Length{Name: l.Name, Value: v, Usage: l.Usage})
	}
	return out, nil
}

func px(s string) (float64, error) {
	v, err := strconv.ParseFloat(strings.TrimSuffix(s, "px"), 64)
	if err != nil || !strings.HasSuffix(s, "px") || math.IsNaN(v) || math.IsInf(v, 0) {
		return 0, fmt.Errorf("%q is not a px length", s)
	}
	return v, nil
}

func resolve(values map[string]map[string]string, name, theme string, depth int) (RGBA, error) {
	if depth > 8 {
		return RGBA{}, fmt.Errorf("colour token %q: alias chain too deep", name)
	}
	v, ok := values[name][theme]
	if !ok {
		return RGBA{}, fmt.Errorf("colour token %q has no %s value", name, theme)
	}
	if m := aliasRe.FindStringSubmatch(v); m != nil {
		if _, ok := values[m[1]]; !ok || m[1] == name {
			return RGBA{}, fmt.Errorf("colour token %q: alias of unknown token %q", name, m[1])
		}
		return resolve(values, m[1], theme, depth+1)
	}
	return parseColor(v)
}

func parseColor(v string) (RGBA, error) {
	if m := hexRe.FindStringSubmatch(v); m != nil {
		n, _ := strconv.ParseUint(m[1], 16, 32)
		a := uint64(0xFF)
		if m[2] != "" {
			a, _ = strconv.ParseUint(m[2], 16, 8)
		}
		return RGBA{uint8(n >> 16), uint8(n >> 8), uint8(n), uint8(a)}, nil // #nosec G115 -- masked by width
	}
	if m := rgbaRe.FindStringSubmatch(v); m != nil {
		var c [3]uint8
		for i := 0; i < 3; i++ {
			n, _ := strconv.Atoi(m[i+1])
			if n > 255 {
				return RGBA{}, fmt.Errorf("%q: channel over 255", v)
			}
			c[i] = uint8(n) // #nosec G115 -- checked above
		}
		a, _ := strconv.ParseFloat(m[4], 64)
		return RGBA{c[0], c[1], c[2], uint8(math.Round(a * 255))}, nil
	}
	return RGBA{}, fmt.Errorf("%q is not a hex, rgba() or alias colour", v)
}

// Luminance is the WCAG 2.2 relative luminance of an opaque colour.
func Luminance(c RGBA) float64 {
	ch := func(v uint8) float64 {
		s := float64(v) / 255
		if s <= 0.04045 {
			return s / 12.92
		}
		return math.Pow((s+0.055)/1.055, 2.4)
	}
	return 0.2126*ch(c.R) + 0.7152*ch(c.G) + 0.0722*ch(c.B)
}

// Contrast is the WCAG 2.2 contrast ratio between two opaque colours.
func Contrast(a, b RGBA) float64 {
	la, lb := Luminance(a), Luminance(b)
	if la < lb {
		la, lb = lb, la
	}
	return (la + 0.05) / (lb + 0.05)
}
