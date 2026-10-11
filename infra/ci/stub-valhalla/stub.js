// SEC-2 CI stub for Valhalla /route. The mode is the value of "stub" in the JSON body (POST) or in the query string
// (GET ?json=...): e.g. {"stub":"slow", ...}. Bodies of the error modes copy what Valhalla 3.9.0 sends (measured
// 2026-10-11): its own timeout is a plain-text 504 "The server didn't respond in time".
var MODES = {
    slow:    { ms: 4000,  status: 200, type: 'application/json', body: '{"code":"Ok","routes":[],"waypoints":[]}' },
    timeout: { ms: 1500,  status: 504, type: 'text/plain',       body: "The server didn't respond in time" },
    hang:    { ms: 15000, status: 200, type: 'application/json', body: '{"code":"Ok","routes":[],"waypoints":[]}' },
    e500:    { ms: 0,     status: 500, type: 'text/plain',       body: 'internal error' },
    e503:    { ms: 0,     status: 503, type: 'text/plain',       body: 'unavailable' },
    e400:    { ms: 0,     status: 400, type: 'application/json;charset=utf-8',
               body: '{"code":"DistanceExceeded","message":"Path distance exceeds the max distance limit."}' },
    ok:      { ms: 0,     status: 200, type: 'application/json', body: '{"code":"Ok","routes":[],"waypoints":[]}' }
};

function route(r) {
    var text = (r.requestText || '') + ' ' + decodeURIComponent(r.variables.args || '');
    var m = text.match(/"stub"\s*:\s*"([a-z0-9]+)"/);
    var mode = MODES[(m && m[1]) || 'ok'] || MODES.ok;
    var send = function () {
        r.headersOut['Content-Type'] = mode.type;
        r.headersOut['Access-Control-Allow-Origin'] = '*';  // Valhalla always adds it; the gateway must hide it
        r.return(mode.status, mode.body);
    };
    if (mode.ms > 0) { setTimeout(send, mode.ms); } else { send(); }
}

export default { route };
