// Lumos - admin-console/shell / Tests for the admin shell guard.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-ARCH-006, TRS-REP-004, TRS-REP-007
// ADRs: -
// Threading: any
// Notes: Uses node:test so the shell needs no test-runner dependency of its own -
// one less thing to license, lock and audit for a module that is currently empty.
//
// Test names are identifiers, not sentences, and they sit inside a describe()
// block. That is not style: the junit reporter writes the describe name as the
// testsuite and the test name as the case, and tools/tracecheck builds matrix
// identifiers of the form <lang>:<module>:<Suite>.<case>. A sentence with spaces
// and brackets produces an identifier nothing can cite. The requirement each
// test covers is named in its comment instead.

import assert from 'node:assert/strict';
import { describe, test } from 'node:test';

import {
  ENVIRONMENTS,
  describe as describeShell,
  isEnvironment,
  rejectExecutableFields,
} from './shell.js';

describe('AdminShell', () => {
  // TRS-REP-004: the four build variants are a closed set, not free text.
  test('fourBuildEnvironmentsAreSeparated', () => {
    assert.deepEqual([...ENVIRONMENTS], ['development', 'test', 'staging', 'production']);
    assert.ok(isEnvironment('production'));
    assert.ok(!isEnvironment('prod'));
    assert.ok(!isEnvironment(''));
  });

  // TRS-ARCH-006: configuration may alter behaviour that already exists, and
  // may never carry a script, HTML fragment or expression to evaluate.
  test('executableShapedConfigurationFieldsAreRejected', () => {
    const offenders = rejectExecutableFields([
      'venue_id',
      'display_name',
      'onLoadScript',
      'welcomeHtml',
      'perk_id',
      'evalExpression',
    ]);
    assert.deepEqual(offenders, ['onLoadScript', 'welcomeHtml', 'evalExpression']);
  });

  // The same guard must not reject an ordinary field set: a rule that rejects
  // everything passes its negative test and is still useless.
  test('ordinaryConfigurationFieldsAreAccepted', () => {
    assert.deepEqual(rejectExecutableFields(['venue_id', 'room_id', 'alt_text']), []);
  });

  // TRS-REP-007: every artefact reports its own version and source revision.
  test('buildProvenanceIsRendered', () => {
    const line = describeShell({
      version: '0.0.0',
      revision: 'abc1234',
      environment: 'development',
    });
    assert.ok(line.includes('0.0.0'));
    assert.ok(line.includes('abc1234'));
    assert.ok(line.includes('development'));
  });
});
