// Lumos - admin-console/shell / Empty admin console shell.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-REP-001, TRS-REP-007, TRS-ARCH-006
// ADRs: ADR-004
// Threading: main-safe
// Notes: WP-01 delivers a shell that compiles clean and nothing else - the admin
// workflow is WP-08. The one piece of real behaviour is a guard: configuration
// may only alter behaviour that is already implemented and may never carry
// executable content (TRS-ARCH-006). Putting that guard in the shell now means
// the console cannot grow a code-shaped config field by accident before the
// feature work starts.

/** Build provenance reported by the console (TRS-REP-007). */
export interface BuildInfo {
  /** Console version. */
  readonly version: string;
  /** Source revision the artefact was built from. */
  readonly revision: string;
  /** Build environment: development, test, staging or production. */
  readonly environment: string;
}

/** The environments a build may target (TRS-REP-004). */
export const ENVIRONMENTS = ['development', 'test', 'staging', 'production'] as const;

/** One of the four supported build environments. */
export type Environment = (typeof ENVIRONMENTS)[number];

/**
 * Reports whether a string names a supported environment.
 *
 * @param value candidate environment name
 * @returns true when the value is one of ENVIRONMENTS
 */
export function isEnvironment(value: string): value is Environment {
  return (ENVIRONMENTS as readonly string[]).includes(value);
}

/**
 * Field-name fragments that would imply the console offers a
 * code-shaped configuration surface. TRS-ARCH-006 forbids configuration from
 * loading executable scripts or arbitrary HTML, and SRS Sec. 13 warns against a
 * console that even *offers* a field implying new executable capability.
 */
const EXECUTABLE_FIELD_FRAGMENTS = [
  'script',
  'html',
  'eval',
  'exec',
  'command',
  'template',
  'expression',
  'callback',
  'javascript',
] as const;

/**
 * Rejects configuration field names that imply executable content.
 *
 * @param fieldNames candidate configuration field names
 * @returns the offending names, empty when the set is acceptable
 */
export function rejectExecutableFields(fieldNames: readonly string[]): string[] {
  const offenders: string[] = [];
  for (const name of fieldNames) {
    const lower = name.toLowerCase();
    for (const fragment of EXECUTABLE_FIELD_FRAGMENTS) {
      if (lower.includes(fragment)) {
        offenders.push(name);
        break;
      }
    }
  }
  return offenders;
}

/**
 * Describes the shell for a smoke check.
 *
 * @param build build provenance
 * @returns a one-line description
 */
export function describe(build: BuildInfo): string {
  return `lumos-admin-console ${build.version} (${build.environment}, ${build.revision})`;
}
