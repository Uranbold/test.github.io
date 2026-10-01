// Minimal typings for the wasm-bindgen glue of @stadiamaps/ferrostar 0.57.0 (the package types only its bundler
// entry, which this app does not use: ADR-0011 §6 instantiates the WASM by hand).
declare module "@stadiamaps/ferrostar/ferrostar_bg.js" {
  export function __wbg_set_wasm(exports: WebAssembly.Exports): void;
  export class RouteAdapter {
    constructor(provider: { Valhalla: { endpointUrl: string; profile: string } });
    parseResponse(response: Uint8Array): unknown;
    free(): void;
  }
  export class NavigationController {
    constructor(route: unknown, config: unknown, shouldRecord: boolean);
    getInitialState(location: unknown): unknown;
    updateUserLocation(location: unknown, state: unknown): unknown;
    advanceToNextStep(state: unknown): unknown;
    free(): void;
  }
}
