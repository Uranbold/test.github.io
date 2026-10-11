// QA stub of Valhalla /route (NAV-001 SEC-2). The mode is the "qa_stub" member of the request JSON (POST body or GET
// ?json=), so the same request shape the clients send reaches it through the real gateway:
//   slow       -> 200 OSRM-shaped body after 4 s (holds a cap slot; AC 49)
//   hang       -> 200 after 15 s, i.e. later than the gateway's 10 s read timeout (AC 48 last sentence)
//   timeout    -> replay of Valhalla 3.9.0's own timeout answer after 2 s (AC 48 as amended 2026-10-11, ADR-0019
//                 Implementation record F2c: with a 1 s setting Valhalla cut the request at 2.0 s): status 504,
//                 plain-text body "The server didn't respond in time", NO Content-Type header
//   timeoutnow -> the same 504 replay without the delay
//   anything else -> 200 at once
// Every mode also sends an upstream Access-Control-Allow-Origin header (like Valhalla), so the gateway's "exactly once"
// CORS rule is exercised on each answer.
var OK = '{"code":"Ok","routes":[{"distance":1,"duration":1,"geometry":"","legs":[]}],"waypoints":[]}';
var VALHALLA_TIMEOUT_BODY = "The server didn't respond in time";
var DELAY = { slow: 4000, hang: 15000, timeout: 2000, timeoutnow: 0 };

function route(r) {
    var text = (r.requestText || '') + ' ' + decodeURIComponent(r.variables.args || '');
    var m = text.match(/"qa_stub"\s*:\s*"([a-z]+)"/);
    var mode = (m && m[1]) || '';
    var ms = DELAY[mode] || 0;
    var send = function () {
        r.headersOut['Access-Control-Allow-Origin'] = '*';  // like Valhalla; the gateway must not pass it through
        if (mode === 'timeout' || mode === 'timeoutnow') {
            // No Content-Type: the stub nginx.conf sets default_type "" on /route (verified with curl -i, 2026-10-11).
            r.return(504, VALHALLA_TIMEOUT_BODY);
            return;
        }
        r.headersOut['Content-Type'] = 'application/json';
        r.return(200, OK);
    };
    if (ms > 0) { setTimeout(send, ms); } else { send(); }
}

export default { route };
