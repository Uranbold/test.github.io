// UX audit 2026-10-13 UX-04 (navigation-ux §2.1): in the rendered banner and turn-row text the politeness particle
// («үү» / «уу») never starts a line alone. The final verb and its particle («эргэнэ үү», «явна уу», «орно уу»,
// «гарна уу», «барина уу», «нийлнэ үү») go into one `white-space: nowrap` span at render time; `textContent` stays
// byte-identical to the resource text, so the NAV-004 / NAV-017 literal-text tests and the golden texts are unchanged.
// Resource strings, voice text and accessible names are never touched (they keep the plain space).

/** `text` split before its final «verb particle» pair, or null when the text has no trailing particle. */
export function splitParticle(text: string): { head: string; tail: string } | null {
  // «үү» = U+04AF U+04AF, «уу» = U+0443 U+0443 (escaped: no Cyrillic literal in source, scripts/check-i18n.mjs).
  const m = /^(.*?)(\S+ (?:\u04AF\u04AF|\u0443\u0443))$/u.exec(text);
  if (!m) return null;
  return { head: m[1]!, tail: m[2]! };
}

/** Renders `text` into `el`: plain text, with the verb + particle wrapped in a no-wrap span when there is one. */
export function setInstruction(el: HTMLElement, text: string): void {
  const parts = splitParticle(text);
  if (!parts) {
    el.textContent = text;
    return;
  }
  const span = document.createElement("span");
  span.className = "nb";
  span.textContent = parts.tail;
  el.replaceChildren(parts.head, span);
}
