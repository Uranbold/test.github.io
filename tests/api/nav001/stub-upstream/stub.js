// QA stub of Valhalla /route (NAV-001 SEC-2). The mode is the "qa_stub" member of the request JSON (POST body or GET
// ?json=), so the same request shape the clients send reaches it through the real gateway:
//   slow -> 200 OSRM-shaped body after 4 s (holds a cap slot; AC 49)
//   hang -> 200 after 15 s, i.e. later than the gateway's 10 s read timeout (AC 48 last sentence)
//   anything else -> 200 at once
var OK = '{"code":"Ok","routes":[{"distance":1,"duration":1,"geometry":"","legs":[]}],"waypoints":[]}';
var DELAY = { slow: 4000, hang: 15000 };

function route(r) {
    var text = (r.requestText || '') + ' ' + decodeURIComponent(r.variables.args || '');
    var m = text.match(/"qa_stub"\s*:\s*"([a-z]+)"/);
    var ms = DELAY[(m && m[1]) || ''] || 0;
    var send = function () {
        r.headersOut['Content-Type'] = 'application/json';
        r.headersOut['Access-Control-Allow-Origin'] = '*';  // like Valhalla; the gateway must not pass it through
        r.return(200, OK);
    };
    if (ms > 0) { setTimeout(send, ms); } else { send(); }
}

export default { route };
