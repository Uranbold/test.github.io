// Static server used by audit-web.mjs (UX audit of the web builds): serves a Vite dist folder at the root and the NAV-001 basemap archive at
// /tiles/basemap.pmtiles with HTTP Range support (206 + Content-Range), CORS *. Usage: node serve.mjs <root> <port> <tiles>
import http from "node:http";
import { createReadStream, statSync, existsSync } from "node:fs";
import { extname, join, normalize } from "node:path";

const [root, portArg, tiles] = process.argv.slice(2);
const port = Number(portArg);
const MIME = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".mjs": "text/javascript", ".css": "text/css", ".json": "application/json", ".wasm": "application/wasm", ".png": "image/png", ".pbf": "application/x-protobuf", ".svg": "image/svg+xml", ".ico": "image/x-icon", ".txt": "text/plain", ".webmanifest": "application/manifest+json", ".pmtiles": "application/octet-stream" };

function send(req, res, file) {
  const st = statSync(file);
  const type = MIME[extname(file)] ?? "application/octet-stream";
  const range = req.headers.range;
  const headers = { "Content-Type": type, "Accept-Ranges": "bytes", "Access-Control-Allow-Origin": "*", "Cache-Control": "no-store" };
  if (range) {
    const m = /bytes=(\d+)-(\d*)/.exec(range);
    const start = Number(m[1]);
    const end = m[2] ? Math.min(Number(m[2]), st.size - 1) : st.size - 1;
    res.writeHead(206, { ...headers, "Content-Range": `bytes ${start}-${end}/${st.size}`, "Content-Length": end - start + 1 });
    if (req.method === "HEAD") return res.end();
    createReadStream(file, { start, end }).pipe(res);
    return;
  }
  res.writeHead(200, { ...headers, "Content-Length": st.size });
  if (req.method === "HEAD") return res.end();
  createReadStream(file).pipe(res);
}

http
  .createServer((req, res) => {
    const url = new URL(req.url, "http://x");
    let p = decodeURIComponent(url.pathname);
    if (p === "/tiles/basemap.pmtiles") return send(req, res, tiles);
    if (p.endsWith("/")) p += "index.html";
    const file = normalize(join(root, p));
    if (!file.startsWith(normalize(root)) || !existsSync(file) || statSync(file).isDirectory()) {
      res.writeHead(404, { "Access-Control-Allow-Origin": "*" });
      return res.end("not found");
    }
    send(req, res, file);
  })
  .listen(port, "127.0.0.1", () => console.log(`serving ${root} on http://127.0.0.1:${port}`));
