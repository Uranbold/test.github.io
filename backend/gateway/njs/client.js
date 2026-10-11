// SEC-4A BE-2: per-client key for every per-IP limit of the gateway (njs, shipped in the pinned nginx image).
// Loaded by nginx.conf (js_import client; js_set $nav_client_key client.key) and read by the generated
// conf.d/01-rate-limits.conf (limit_req), 02-route-limits.conf (SEC-2 limit_conn) and 03-download-limits.conf (BE-3).
//
//   IPv4 client                    -> the full address              ("203.0.113.7")
//   IPv4-mapped IPv6 (::ffff:a.b.c.d) -> the IPv4 address            ("203.0.113.7")
//   IPv6 client                    -> its /64 prefix                 ("2001:db8:1:2::/64")
//
// Why: one VPS or phone usually holds a whole /64 (2^64 addresses), so a key per /128 lets one client rotate
// addresses and bypass every per-IP limit (and fill the zones). $remote_addr is the real client address: on
// staging the realip module has already replaced the peer with Caddy's X-Forwarded-For (GATEWAY_REAL_IP_FROM).
// Nothing is logged; the key lives only in the limit zones' shared memory, like $binary_remote_addr before.
// Evaluated lazily: only requests that reach a limited path compute it.

function v6prefix64(addr) {
    let s = addr.toLowerCase();
    const zone = s.indexOf('%');
    if (zone >= 0) {
        s = s.slice(0, zone);
    }
    const mapped = /^::ffff:(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})$/.exec(s);
    if (mapped) {
        return mapped[1];
    }
    let head = [];
    let tail = [];
    const dc = s.indexOf('::');
    if (dc >= 0) {
        if (dc > 0) {
            head = s.slice(0, dc).split(':');
        }
        if (dc + 2 < s.length) {
            tail = s.slice(dc + 2).split(':');
        }
    } else {
        head = s.split(':');
    }
    // An embedded dotted quad (last 32 bits) counts as two groups.
    let tailGroups = tail.length;
    if (tailGroups > 0 && tail[tailGroups - 1].indexOf('.') >= 0) {
        tailGroups += 1;
    }
    let headGroups = head.length;
    if (dc < 0 && headGroups > 0 && head[headGroups - 1].indexOf('.') >= 0) {
        headGroups += 1;
    }
    const groups = [];
    for (let i = 0; i < head.length && groups.length < 4; i++) {
        groups.push(head[i]);
    }
    for (let z = 8 - headGroups - tailGroups; z > 0 && groups.length < 4; z--) {
        groups.push('0');
    }
    for (let i = 0; i < tail.length && groups.length < 4; i++) {
        groups.push(tail[i]);
    }
    const out = [];
    for (let i = 0; i < 4; i++) {
        const g = groups[i];
        if (g === undefined || !/^[0-9a-f]{1,4}$/.test(g)) {
            return addr;   // not a parsable IPv6 address: fall back to the full text (never seen from nginx)
        }
        out.push(parseInt(g, 16).toString(16));
    }
    return out.join(':') + '::/64';
}

function key(r) {
    const addr = r.variables.remote_addr || '';
    if (addr.indexOf(':') < 0) {
        return addr;
    }
    return v6prefix64(addr);
}

export default { key, v6prefix64 };
