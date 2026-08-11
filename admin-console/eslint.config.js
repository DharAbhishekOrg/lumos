// Lumos - admin-console / ESLint flat config for the TypeScript shell.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CODE-006, TRS-CICD-001
// ADRs: -
// Threading: n/a
// Notes: Zero warnings is the gate (TRS-CODE-006), enforced by --max-warnings=0
// in the lint script rather than by trusting the default. Type-aware rules are
// deliberately not enabled yet: the shell has almost no code to check and the
// project-service setup would fail on an empty source tree.
import js from '@eslint/js';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    rules: {
      'no-console': 'off',
      '@typescript-eslint/explicit-function-return-type': 'error',
    },
  },
);
