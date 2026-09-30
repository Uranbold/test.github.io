// NAV-001 gateway header fix-ups (njs, shipped in the pinned nginx image).
//
// range416: header filter for @range_not_satisfiable only.
// nginx's range filter turns the tiles 200 into a 416 AFTER add_header has run in the TILES location,
// and those headers survive the internal redirect to the 416 location. Stock nginx cannot remove a
// header once it is set, so this filter rewrites them on the final 416 response:
//   - Cache-Control: "public, max-age=300" (file policy) -> "no-store" (an error must not be cached)
//   - Accept-Ranges: removed (describes the file response, not the error)
// CORS headers, Content-Range (bytes */<size>) and the JSON body are left untouched.
function range416(r) {
    r.headersOut['Cache-Control'] = 'no-store';
    delete r.headersOut['Accept-Ranges'];
}

export default { range416 };
