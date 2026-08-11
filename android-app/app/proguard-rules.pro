# Lumos - android-app/app / R8 rules for the empty shell.
# Author: dhar.abhishek.email@gmail.com
# Implements: TRS-REP-001
# ADRs: -
# Threading: n/a
# Notes: Empty by design. The shell has no code to keep; real rules arrive with
# the modules that need them, and a speculative -keep now would silently disable
# shrinking for classes nobody has written.
