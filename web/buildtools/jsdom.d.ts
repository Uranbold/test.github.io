// Minimal typing for the part of jsdom (devDependency, MIT) that buildtools/csp.ts uses. jsdom ships no types and
// @types/jsdom is not a dependency; this keeps `npm run typecheck` strict without adding a package (SEC-4B).
declare module "jsdom" {
  export class JSDOM {
    constructor(html?: string, options?: Record<string, unknown>);
    readonly window: { readonly document: Document; close(): void };
  }
}
