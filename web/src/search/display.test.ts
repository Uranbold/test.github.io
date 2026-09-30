// Story AC 17–20 (name, type label, context line, zoom), AC 8 and 15 (merge), openapi PhotonProperties.
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import en from "../i18n/en.json";
import mn from "../i18n/mn.json";
import { contextLine, dataName, resultInfo, TYPE_LABEL_RULES, typeLabelKey, zoomForType, type PlaceTypeKey } from "./display";
import { mergeFeatures, mnFirst, MAX_OPTIONS } from "./merge";
import { featureKey, parseFeatureCollection, type PhotonFeature, type PhotonProperties } from "./photon";

const feature = (properties: PhotonProperties, lon = 106.9176, lat = 47.9189): PhotonFeature => ({
  type: "Feature",
  geometry: { type: "Point", coordinates: [lon, lat] },
  properties,
});

/** One fixture per rule of the story table "Type labels" (rule order), with the expected mn / en label. */
const RULES: Array<[number | "4a", PhotonProperties, PlaceTypeKey, string, string]> = [
  [1, { name: "Баянзүрх дүүрэг", osm_key: "boundary", osm_value: "administrative" }, "placeType.district", "Дүүрэг", "District"],
  [2, { name: "БЗД-ийн 4-р хороо", osm_key: "office", osm_value: "government" }, "placeType.khoroo", "Хороо", "Khoroo"],
  [3, { name: "Сэлэнгэ аймаг", osm_key: "boundary", osm_value: "administrative" }, "placeType.aimag", "Аймаг", "Aimag"],
  [4, { name: "Баруунхараа сум", osm_key: "place", osm_value: "village" }, "placeType.soum", "Сум", "Soum"],
  ["4a", { name: "Chingeltei", osm_key: "boundary", osm_value: "administrative", type: "district" }, "placeType.district", "Дүүрэг", "District"],
  [5, { name: "Эрдэнэт", osm_key: "place", osm_value: "city" }, "placeType.city", "Хот", "City or town"],
  [6, { name: "Тэрэлж", osm_key: "place", osm_value: "hamlet" }, "placeType.settlement", "Суурин", "Settlement"],
  [7, { name: "Бага Тойрог", osm_key: "place", osm_value: "suburb" }, "placeType.neighbourhood", "Хороолол", "Neighbourhood"],
  [8, { name: "Сүхбаатарын талбай", osm_key: "place", osm_value: "square" }, "placeType.square", "Талбай", "Square"],
  [9, { name: "Шунхлай", osm_key: "amenity", osm_value: "fuel" }, "placeType.fuel", "ШТС", "Petrol station"],
  [10, { name: "Интермед", osm_key: "amenity", osm_value: "clinic" }, "placeType.hospital", "Эмнэлэг", "Hospital or clinic"],
  [11, { name: "Моннис", osm_key: "amenity", osm_value: "pharmacy" }, "placeType.pharmacy", "Эмийн сан", "Pharmacy"],
  [12, { name: "1-р сургууль", osm_key: "amenity", osm_value: "school" }, "placeType.school", "Сургууль", "School"],
  [13, { name: "МУИС", osm_key: "amenity", osm_value: "university" }, "placeType.university", "Их сургууль", "University or college"],
  [14, { name: "Модерн номад", osm_key: "amenity", osm_value: "cafe" }, "placeType.restaurant", "Хоолны газар", "Restaurant or café"],
  [15, { name: "Чингис", osm_key: "tourism", osm_value: "hostel" }, "placeType.hotel", "Зочид буудал", "Hotel"],
  [16, { name: "Улсын их дэлгүүр", osm_key: "shop", osm_value: "department_store" }, "placeType.mall", "Худалдааны төв", "Shopping centre"],
  [17, { name: "Номин", osm_key: "shop", osm_value: "supermarket" }, "placeType.shop", "Дэлгүүр", "Shop"],
  [18, { name: "Нарантуул", osm_key: "amenity", osm_value: "marketplace" }, "placeType.market", "Зах", "Market"],
  [19, { name: "Хаан банк", osm_key: "amenity", osm_value: "atm" }, "placeType.bank", "Банк", "Bank or ATM"],
  [20, { name: "Наран их дэлгүүр", osm_key: "highway", osm_value: "bus_stop" }, "placeType.busStop", "Автобусны буудал", "Bus stop"],
  [20, { name: "Буудал", osm_key: "public_transport", osm_value: "platform" }, "placeType.busStop", "Автобусны буудал", "Bus stop"],
  [21, { name: "Улаанбаатар өртөө", osm_key: "railway", osm_value: "station" }, "placeType.railwayStation", "Галт тэрэгний буудал", "Railway station"],
  [22, { name: "Чингис хаан", osm_key: "aeroway", osm_value: "aerodrome" }, "placeType.airport", "Нисэх онгоцны буудал", "Airport"],
  [23, { name: "Зогсоол", osm_key: "amenity", osm_value: "parking" }, "placeType.parking", "Зогсоол", "Parking"],
  [24, { name: "Үндэсний музей", osm_key: "tourism", osm_value: "museum" }, "placeType.museum", "Музей", "Museum"],
  [25, { name: "Зайсан толгой", osm_key: "historic", osm_value: "memorial" }, "placeType.historic", "Дурсгалт газар", "Monument or historic site"],
  [25, { name: "Viewpoint", osm_key: "tourism", osm_value: "viewpoint" }, "placeType.historic", "Дурсгалт газар", "Monument or historic site"],
  [26, { name: "Гандан", osm_key: "amenity", osm_value: "place_of_worship" }, "placeType.worship", "Сүм хийд", "Place of worship"],
  [27, { name: "Үндэсний цэцэрлэгт хүрээлэн", osm_key: "leisure", osm_value: "park" }, "placeType.park", "Цэцэрлэгт хүрээлэн", "Park"],
  [28, { name: "Засгийн газрын ордон", osm_key: "amenity", osm_value: "townhall" }, "placeType.government", "Төрийн байгууллага", "Government office"],
  [29, { name: "Embassy", osm_key: "office", osm_value: "diplomatic" }, "placeType.embassy", "Элчин сайдын яам", "Embassy"],
  [30, { name: "Энхтайвны өргөн чөлөө", osm_key: "highway", osm_value: "primary" }, "placeType.road", "Зам", "Road"],
  [31, { housenumber: "12", street: "Сөүлийн гудамж", osm_key: "building", osm_value: "yes" }, "placeType.address", "Хаяг", "Address"],
  [32, { name: "Something", osm_key: "man_made", osm_value: "tower" }, "placeType.place", "Газар", "Place"],
];

describe("type labels (story table, AC 19)", () => {
  it("has 32 explicit rules (1–4, 4a, 5–31) plus the «Газар» fallback, in story order", () => {
    expect(TYPE_LABEL_RULES).toHaveLength(32);
    expect(TYPE_LABEL_RULES[4]?.[0]).toBe("placeType.district");
    expect(TYPE_LABEL_RULES[5]?.[0]).toBe("placeType.city");
  });
  for (const [rule, props, key, mnLabel, enLabel] of RULES) {
    it(`rule ${rule}: ${props.osm_key}=${props.osm_value} ${props.name ?? ""} → «${mnLabel}» / "${enLabel}"`, () => {
      expect(typeLabelKey(props)).toBe(key);
      expect(mn[key]).toBe(mnLabel);
      expect(en[key]).toBe(enLabel);
    });
  }
  it("first match wins: a «… хороо» name beats office=government; «… сум» needs boundary or place", () => {
    expect(typeLabelKey({ name: "1-р хороо", osm_key: "office", osm_value: "government" })).toBe("placeType.khoroo");
    expect(typeLabelKey({ name: "Сум", osm_key: "place", osm_value: "village" })).toBe("placeType.settlement");
    expect(typeLabelKey({ name: "Хуучин сум", osm_key: "amenity", osm_value: "place_of_worship" })).toBe("placeType.worship");
  });
  it("rules 1–4 also match the Latin endings returned with lang=en, case-insensitively (F2)", () => {
    const cases: Array<[string, PlaceTypeKey]> = [
      ["Bayanzurkh duureg", "placeType.district"],
      ["Bayanzürkh Düüreg", "placeType.district"],
      ["Bayanzürkh düüreg".normalize("NFD"), "placeType.district"],
      ["Khan-Uul District", "placeType.district"],
      ["Chingeltei duureg, 5-g khoroo", "placeType.khoroo"],
      ["4-r horoo", "placeType.khoroo"],
      ["Selenge aimag", "placeType.aimag"],
      ["Selenge Province", "placeType.aimag"],
    ];
    for (const [name, key] of cases) expect(typeLabelKey({ name, osm_key: "office", osm_value: "government" }), name).toBe(key);
    expect(typeLabelKey({ name: "Baruunkharaa sum", osm_key: "place", osm_value: "village" })).toBe("placeType.soum");
    expect(typeLabelKey({ name: "Bornuur soum", osm_key: "boundary", osm_value: "administrative" })).toBe("placeType.soum");
    // «… sum» / "… soum" still needs osm_key boundary or place (rule 4).
    expect(typeLabelKey({ name: "Bornuur soum", osm_key: "amenity", osm_value: "fuel" })).toBe("placeType.fuel");
  });
  it("an ending must follow a space: \"Khoroo 5\", \"Districtbar\" and a bare «Хороо» do not match rules 1–4", () => {
    expect(typeLabelKey({ name: "Khoroo 5", osm_key: "office", osm_value: "government" })).toBe("placeType.government");
    expect(typeLabelKey({ name: "Хороо", osm_key: "office", osm_value: "government" })).toBe("placeType.government");
    expect(typeLabelKey({ name: "Mydistrict", osm_key: "place", osm_value: "suburb" })).toBe("placeType.neighbourhood");
  });
  it("rule 4a needs boundary=administrative and type=district", () => {
    expect(typeLabelKey({ name: "Chingeltei", osm_key: "boundary", osm_value: "administrative" })).toBe("placeType.place");
    expect(typeLabelKey({ name: "Chingeltei", osm_key: "boundary", osm_value: "administrative", type: "city" })).toBe("placeType.place");
    expect(typeLabelKey({ name: "Chingeltei", osm_key: "boundary", osm_value: "protected_area", type: "district" })).toBe("placeType.place");
    // place=suburb with type=district and no district ending stays rule 7 (only boundaries use rule 4a).
    expect(typeLabelKey({ name: "Bayanzurkh", osm_key: "place", osm_value: "suburb", type: "district" })).toBe("placeType.neighbourhood");
    // A name ending still wins over rule 4a (R13: a soum boundary that Photon classes as type=district).
    expect(typeLabelKey({ name: "Bornuur sum", osm_key: "boundary", osm_value: "administrative", type: "district" })).toBe("placeType.soum");
    expect(typeLabelKey({ name: "Сэлэнгэ аймаг", osm_key: "boundary", osm_value: "administrative", type: "district" })).toBe("placeType.aimag");
  });
  it("uses the name after removing traditional script (AC 18)", () => {
    expect(typeLabelKey({ name: "Сэлэнгэ аймаг ᠰᠡᠯᠡᠩᠭᠡ", osm_key: "boundary" })).toBe("placeType.aimag");
  });
  it("zoom 13 for area types, 16 for all others (AC 20)", () => {
    for (const k of ["placeType.city", "placeType.settlement", "placeType.district", "placeType.khoroo", "placeType.neighbourhood", "placeType.aimag", "placeType.soum"] as const) {
      expect(zoomForType(k)).toBe(13);
    }
    expect(zoomForType("placeType.square")).toBe(16);
    expect(zoomForType("placeType.place")).toBe(16);
  });
});

/**
 * Story fixtures F14–F16 (PO approval F2): each rendered in both UI languages. The label key is language-independent;
 * the UI language only picks mn.json or en.json for that key, so both languages show the label of the same row.
 */
describe("fixtures F14–F16 (AC 19, 20; F2)", () => {
  const CHINGELTEI_EXTENT: [number, number, number, number] = [106.8636, 48.0412, 106.9406, 47.9119];
  const boundary = { osm_type: "R", osm_id: 2798006, osm_key: "boundary", osm_value: "administrative", type: "district" } as const;
  const f14en = feature({ ...boundary, name: "Chingeltei", extent: CHINGELTEI_EXTENT, countrycode: "MN" }, 106.9, 47.95);
  const f14mn = feature({ ...boundary, name: "Чингэлтэй дүүрэг", extent: CHINGELTEI_EXTENT, countrycode: "MN" }, 106.9, 47.95);
  const f14mnShort = feature({ ...boundary, name: "Чингэлтэй", extent: CHINGELTEI_EXTENT, countrycode: "MN" }, 106.9, 47.95);
  const f15 = feature({ osm_type: "N", osm_id: 1, name: "Chingeltei duureg, 5-g khoroo", osm_key: "office", osm_value: "government" }, 106.91, 47.93);
  const f16 = feature({ osm_type: "R", osm_id: 2, name: "Сүхбаатар дүүрэг", osm_key: "place", osm_value: "suburb", type: "district" });

  const labels = (f: PhotonFeature): [PlaceTypeKey, string, string] => {
    const k = resultInfo(f).typeKey;
    return [k, mn[k], en[k]];
  };

  it("F14: the düüreg boundary is «Дүүрэг» / \"District\" for the lang=en name \"Chingeltei\" and the lang=mn names", () => {
    for (const f of [f14en, f14mn, f14mnShort]) {
      expect(labels(f), f.properties.name).toEqual(["placeType.district", "Дүүрэг", "District"]);
      // With extent: fit the box (AC 20), identical in both languages.
      expect(resultInfo(f).bounds).toEqual([[106.8636, 47.9119], [106.9406, 48.0412]]);
    }
    // Same row in both languages (AC 19): never "Place" in one and a specific label in the other.
    expect(resultInfo(f14en).typeKey).toBe(resultInfo(f14mn).typeKey);
    // Without extent it would fly to the area zoom.
    expect(zoomForType(resultInfo(f14en).typeKey)).toBe(13);
  });

  it("F15: \"Chingeltei duureg, 5-g khoroo\" (office=government, no extent) is «Хороо» / \"Khoroo\" at zoom 13", () => {
    expect(labels(f15)).toEqual(["placeType.khoroo", "Хороо", "Khoroo"]);
    const info = resultInfo(f15);
    expect(info.bounds).toBeNull();
    expect(zoomForType(info.typeKey)).toBe(13);
  });

  it("F16: «Сүхбаатар дүүрэг» mapped as place=suburb, type=district is rule 1 «Дүүрэг», not rule 7 «Хороолол»", () => {
    expect(labels(f16)).toEqual(["placeType.district", "Дүүрэг", "District"]);
    expect(labels(feature({ ...f16.properties, name: "Sukhbaatar duureg" }))).toEqual(["placeType.district", "Дүүрэг", "District"]);
  });
});

describe("name and context line (AC 17, 18)", () => {
  it("name falls back to street + housenumber, then to null (type label)", () => {
    expect(dataName({ name: "Сүхбаатарын талбай" })).toBe("Сүхбаатарын талбай");
    expect(dataName({ street: "Сөүлийн гудамж", housenumber: "12" })).toBe("Сөүлийн гудамж 12");
    expect(dataName({ name: "ᠮᠤᠩᠭᠤᠯ", street: "Бага тойруу" })).toBe("Бага тойруу");
    expect(dataName({ osm_key: "building" })).toBeNull();
  });
  it("first 2 distinct non-empty values of district, locality, city, county, state, skipping the name", () => {
    expect(contextLine({ district: "Бага Тойрог", city: "Улаанбаатар", state: "Улаанбаатар" }, "Сүхбаатарын талбай")).toBe("Бага Тойрог, Улаанбаатар");
    // district equal to the name is skipped (F-fixture "district equals name")
    expect(contextLine({ district: "Гандан", city: "Улаанбаатар" }, "Гандан")).toBe("Улаанбаатар");
    // traditional script only → next field
    expect(contextLine({ county: "ᠰᠦᠬᠡ", state: "Сүхбаатар ᠰᠦᠬᠡ ᠪᠠᠭᠠᠲᠤᠷ" }, "X")).toBe("Сүхбаатар");
    expect(contextLine({ country: "Монгол улс ᠮᠤᠩᠭᠤᠯ ᠤᠯᠤᠰ" }, "X")).toBeNull();
    expect(contextLine({}, null)).toBeNull();
  });
  it("resultInfo reorders Photon's [minLon, maxLat, maxLon, minLat] extent for fitBounds", () => {
    const i = resultInfo(feature({ name: "Сүхбаатар дүүрэг", extent: [106.86347, 48.197306, 107.03429, 47.9080639] }, 106.95, 48));
    expect(i.bounds).toEqual([[106.86347, 47.9080639], [107.03429, 48.197306]]);
    expect(i.typeKey).toBe("placeType.district");
    expect(resultInfo(feature({ name: "x", extent: [1, 1, 1, 1] })).bounds).toBeNull();
  });
});

describe("merge (AC 8, 15)", () => {
  const f = (id: number, cc = "MN"): PhotonFeature => feature({ osm_type: "N", osm_id: id, name: `n${id}`, countrycode: cc });
  it("interleaves, drops duplicates by osm_type + osm_id, MN first, max 10", () => {
    const merged = mergeFeatures([f(1), f(2, "CN"), f(3)], [f(3), f(4), f(5)]);
    expect(merged.map((x) => x.properties.osm_id)).toEqual([1, 3, 4, 5, 2]);
    const many = mergeFeatures(Array.from({ length: 8 }, (_, i) => f(i)), Array.from({ length: 8 }, (_, i) => f(100 + i)));
    expect(many).toHaveLength(MAX_OPTIONS);
  });
  it("mnFirst keeps the upstream order inside each group", () => {
    expect(mnFirst([f(1, "CN"), f(2), f(3, "RU"), f(4)]).map((x) => x.properties.osm_id)).toEqual([2, 4, 1, 3]);
    expect(mnFirst([f(1, "")]).length).toBe(1);
  });
  it("features without osm ids are de-duplicated by name and point", () => {
    expect(featureKey(feature({ name: "a" }))).toBe(featureKey(feature({ name: "a" })));
  });
});

describe("parseFeatureCollection (openapi PhotonFeatureCollection)", () => {
  it("accepts a Photon response and drops malformed features and fields", () => {
    const fc = parseFeatureCollection({
      type: "FeatureCollection",
      features: [
        { type: "Feature", geometry: { type: "Point", coordinates: [106.9, 47.9] }, properties: { osm_type: "N", osm_id: 1, name: "a", extent: [1, 2] } },
        { type: "Feature", geometry: { type: "Point", coordinates: ["x", 47.9] }, properties: {} },
        { type: "Feature", geometry: { type: "Point", coordinates: [106.9, 47.9] }, properties: { name: 5, osm_type: "X" } },
      ],
    });
    expect(fc?.features).toHaveLength(2);
    expect(fc?.features[0]?.properties.extent).toBeUndefined();
    expect(fc?.features[1]?.properties.name).toBeUndefined();
    expect(fc?.features[1]?.properties.osm_type).toBeUndefined();
  });
  it("rejects bodies that are not a FeatureCollection", () => {
    expect(parseFeatureCollection("<html>")).toBeNull();
    expect(parseFeatureCollection({ type: "FeatureCollection" })).toBeNull();
    expect(parseFeatureCollection(null)).toBeNull();
  });
});

describe("contract: openapi.yaml (read-only input)", () => {
  const yaml = readFileSync(new URL("../../../docs/architecture/api/openapi.yaml", import.meta.url), "utf8");
  const block = (start: RegExp, end: RegExp): string => {
    const s = yaml.search(start);
    const rest = yaml.slice(s);
    const e = rest.slice(1).search(end);
    return e < 0 ? rest : rest.slice(0, e + 1);
  };
  it("search and reverse operations and the query parameters the client sends exist", () => {
    const search = block(/\n {2}\/v1\/search:/, /\n {2}\/v1\//);
    expect(search).toMatch(/operationId: search\b/);
    for (const p of ["name: q", "PhotonLat", "PhotonLon", "PhotonLimit", "PhotonLang"]) expect(search).toContain(p);
    const reverse = block(/\n {2}\/v1\/reverse:/, /\n {2}\/[a-z]|\ncomponents:/);
    expect(reverse).toMatch(/operationId: reverse\b/);
    for (const p of ["name: lat", "name: lon", "name: radius", "PhotonLimit", "PhotonLang"]) expect(reverse).toContain(p);
    expect(search).toMatch(/"429":\s*\n\s*\$ref: "#\/components\/responses\/RateLimited"/);
  });
  it("every PhotonProperties field the client reads is in the schema", () => {
    const props = block(/\n {4}PhotonProperties:/, /\n {4}[A-Z][A-Za-z]+:\n/);
    for (const k of ["osm_type", "osm_id", "osm_key", "osm_value", "name", "housenumber", "street", "locality", "district", "city", "county", "state", "countrycode", "extent"]) {
      expect(props, k).toMatch(new RegExp(`\\n\\s+${k}:`));
    }
    expect(props).toContain("[minLon, maxLat, maxLon, minLat]");
  });
});
