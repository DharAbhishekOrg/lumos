// Lumos - tools/tokensgen / Every promised colour pair, held to WCAG 2.2 AA.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-NFR-A11Y-002, TRS-UX-002
// ADRs: -
// Threading: any
// Notes: The token file's usage notes promise which pairs are readable ("Body
// text passes AA on it"). This list is those promises, and the generator
// refuses to write anything while one is broken, in either theme: a token
// tweak that makes Daylight captions unreadable fails before it reaches a
// screen. Text is held to 4.5:1. Control borders, icons and the ticks are
// non-text graphics, held to 3:1 (WCAG 1.4.11).
package main

import "fmt"

// Pair is one promised foreground/background combination.
type Pair struct {
	Fg, Bg string
	Min    float64
}

const (
	textAA    = 4.5
	nonTextAA = 3.0
)

// PromisedPairs are the combinations the design system says are readable.
func PromisedPairs() []Pair {
	var out []Pair
	text := func(fgs []string, bgs ...string) {
		for _, f := range fgs {
			for _, b := range bgs {
				out = append(out, Pair{f, b, textAA})
			}
		}
	}
	graphic := func(fgs []string, bgs ...string) {
		for _, f := range fgs {
			for _, b := range bgs {
				out = append(out, Pair{f, b, nonTextAA})
			}
		}
	}
	inks := []string{"ink", "ink-muted", "ink-subtle"}
	text(inks, "canvas", "surface", "surface-raised")
	text([]string{"ink", "ink-muted"}, "surface-high", "violet-soft", "bubble-out", "bubble-in")
	text([]string{"on-violet"}, "violet")
	// The Material theme's on-colours: text on a filled aqua, coral or danger control.
	text([]string{"canvas"}, "aqua", "coral", "danger")
	text([]string{"violet"}, "canvas", "surface", "violet-soft")
	text([]string{"aqua", "coral", "amber", "danger"}, "surface")
	text([]string{"danger"}, "surface-high", "surface-raised")
	text([]string{"aqua"}, "canvas", "bubble-in")
	graphic([]string{"outline"}, "canvas", "surface", "surface-high")
	graphic([]string{"read", "tick"}, "surface", "bubble-out", "bubble-in")
	return out
}

// CheckContrast lists every promised pair that fails, in either theme.
func CheckContrast(t *Tokens) []string {
	byName := map[string]Color{}
	for _, c := range t.Colors {
		byName[c.Name] = c
	}
	var failures []string
	for _, p := range PromisedPairs() {
		fg, ok1 := byName[p.Fg]
		bg, ok2 := byName[p.Bg]
		if !ok1 || !ok2 {
			failures = append(failures, fmt.Sprintf("%s on %s: a promised token is missing", p.Fg, p.Bg))
			continue
		}
		for _, th := range []struct {
			name   string
			fg, bg RGBA
		}{{"Midnight", fg.Midnight, bg.Midnight}, {"Daylight", fg.Daylight, bg.Daylight}} {
			if r := Contrast(th.fg, th.bg); r < p.Min {
				failures = append(failures, fmt.Sprintf("%s: %s on %s is %.2f:1, needs %.1f:1", th.name, p.Fg, p.Bg, r, p.Min))
			}
		}
	}
	return failures
}
