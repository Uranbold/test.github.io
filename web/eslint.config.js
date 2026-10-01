import js from "@eslint/js";
import tseslint from "typescript-eslint";

// AC 32: no user-facing text literal outside src/i18n/{mn,en}.json.
// These selectors catch string/template literals assigned to text or accessible-name properties.
// scripts/check-i18n.mjs adds a scripted scan (Cyrillic anywhere, setAttribute, index.html text).
const TEXT_PROPS = "/^(textContent|innerText|title|ariaLabel|ariaDescription|placeholder|alt)$/";
const noHardcodedText = [
  {
    selector: `AssignmentExpression[left.property.name=${TEXT_PROPS}][right.type='Literal'][right.value=/[A-Za-z\\u0400-\\u04FF]/]`,
    message: "User-facing text must come from src/i18n (t(key)), not a literal (NAV-002 AC 32).",
  },
  {
    // Template literals are fine when they only join translated parts, e.g. `${n} ${t("unit.km")}`.
    selector: `AssignmentExpression[left.property.name=${TEXT_PROPS}] > TemplateLiteral > TemplateElement[value.raw=/[A-Za-z\\u0400-\\u04FF]/]`,
    message: "User-facing text must come from src/i18n (t(key)), not a template literal (NAV-002 AC 32).",
  },
  {
    selector: "CallExpression[callee.property.name='setAttribute'][arguments.0.value=/^(aria-label|aria-description|title|alt|placeholder)$/][arguments.1.type='Literal']",
    message: "Accessible names must come from src/i18n (t(key)) (NAV-002 AC 32).",
  },
];

export default tseslint.config(
  { ignores: ["dist/**", "dist-static-demo/**", "dist-demo-mode/**", "node_modules/**", "public/**"] },
  {
    files: ["buildtools/**/*.ts"],
    languageOptions: { globals: { process: "readonly", console: "readonly", URL: "readonly" } },
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ["src/**/*.ts", "fixtures/**/*.ts"],
    ignores: ["src/**/*.test.ts"],
    rules: {
      "no-restricted-syntax": ["error", ...noHardcodedText],
    },
  },
  {
    files: ["scripts/**/*.mjs", "*.config.*"],
    languageOptions: {
      globals: { process: "readonly", console: "readonly", URL: "readonly" },
    },
  },
);
