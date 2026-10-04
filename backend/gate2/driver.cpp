// NAV-020 Gate 2 engine driver (ADR-0017 §4 and Amendment A1 items 1-2). The ONLY code of ours in the Gate 2 image.
//
// Reads one request per line on stdin:   {"id": "<request id>", "body": { ...Valhalla route request... }}
// Writes one line per request on stdout:  {"id": "<request id>", "response": "<raw response string>"}
// The response is exactly what the shipped library returns: the serialized route in the requested format, or, on
// an error, the valhalla-mobile 0.6.3 error envelope {"code": <valhalla error code>, "message": "..."} (the same
// shape as error_json() in valhalla-mobile src/wrapper/main.cpp at tag 0.6.3). Nothing is logged per request.
//
// Variant "wrapper" (default, A1 item 1): calls ValhallaActor::route(json) of the wrapper at commit b47ad5a9...,
// the C++ entry point that the Android JNI `route` calls (it runs each action on a 16 MB-stack thread).
// Variant "upstream" (A1 item 2, compile with -DNAV_GATE2_UPSTREAM): when the wrapper target does not build on
// Linux, calls valhalla::tyr::actor_t::route of Valhalla e2f017b1... directly on a 16 MB-stack thread, which is
// what ValhallaActor does.
//
//   gate2-driver /data/config.json < requests.jsonl > responses.jsonl
#include <cstdint>
#include <exception>
#include <iostream>
#include <memory>
#include <string>
#include <system_error>

#include <pthread.h>

#include <rapidjson/document.h>
#include <rapidjson/stringbuffer.h>
#include <rapidjson/writer.h>

#include <valhalla/exceptions.h>

#ifdef NAV_GATE2_UPSTREAM
#include <boost/property_tree/ptree.hpp>
#include <valhalla/baldr/graphreader.h>
#include <valhalla/baldr/rapidjson_utils.h>
#include <valhalla/tyr/actor.h>
#else
#include "valhalla_actor.h"
#endif

namespace {

std::string error_json(int64_t code, const std::string& message) {
  rapidjson::StringBuffer sb;
  rapidjson::Writer<rapidjson::StringBuffer> w(sb);
  w.StartObject();
  w.Key("code");
  w.Int64(code);
  w.Key("message");
  w.String(message.c_str(), static_cast<rapidjson::SizeType>(message.size()));
  w.EndObject();
  return sb.GetString();
}

#ifdef NAV_GATE2_UPSTREAM
// Same stack size as valhalla-mobile's run_on_deep_stack (kActorStackSize).
constexpr size_t kStack = 16 * 1024 * 1024;

struct Call {
  valhalla::tyr::actor_t* actor;
  const std::string* request;
  std::string result;
  std::exception_ptr error;
};

void* run_call(void* arg) {
  auto* c = static_cast<Call*>(arg);
  try {
    c->result = c->actor->route(*c->request);
  } catch (...) {
    c->error = std::current_exception();
  }
  return nullptr;
}

class Engine {
 public:
  explicit Engine(const std::string& config_path) {
    boost::property_tree::ptree config;
    rapidjson::read_json(config_path, config);
    reader_ = std::make_unique<valhalla::baldr::GraphReader>(config.get_child("mjolnir"));
    actor_ = std::make_unique<valhalla::tyr::actor_t>(config, *reader_, true);
  }
  std::string route(const std::string& request) {
    Call c{actor_.get(), &request, {}, nullptr};
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, kStack);
    pthread_t t;
    int rc = pthread_create(&t, &attr, &run_call, &c);
    pthread_attr_destroy(&attr);
    if (rc != 0) throw std::system_error(rc, std::generic_category(), "pthread_create");
    pthread_join(t, nullptr);
    if (c.error) std::rethrow_exception(c.error);
    return c.result;
  }

 private:
  std::unique_ptr<valhalla::baldr::GraphReader> reader_;
  std::unique_ptr<valhalla::tyr::actor_t> actor_;
};
#else
using Engine = ValhallaActor;
#endif

std::string response_line(const std::string& id, const char* key, const std::string& value) {
  rapidjson::StringBuffer sb;
  rapidjson::Writer<rapidjson::StringBuffer> w(sb);
  w.StartObject();
  w.Key("id");
  w.String(id.c_str(), static_cast<rapidjson::SizeType>(id.size()));
  w.Key(key);
  w.String(value.c_str(), static_cast<rapidjson::SizeType>(value.size()));
  w.EndObject();
  return sb.GetString();
}

}  // namespace

int main(int argc, char** argv) {
  const std::string config = argc > 1 ? argv[1] : "/data/config.json";
  std::unique_ptr<Engine> engine;
  try {
    engine = std::make_unique<Engine>(config);
  } catch (const std::exception& e) {
    std::cerr << "{\"job\":\"gate2-driver\",\"level\":\"error\",\"msg\":\"engine did not start\"}" << std::endl;
    return 3;
  }
  std::string line;
  while (std::getline(std::cin, line)) {
    if (line.empty()) continue;
    rapidjson::Document d;
    d.Parse(line.c_str());
    if (d.HasParseError() || !d.IsObject() || !d.HasMember("id") || !d["id"].IsString() || !d.HasMember("body")) {
      std::cout << response_line("", "error", "bad request line") << '\n' << std::flush;
      continue;
    }
    const std::string id = d["id"].GetString();
    rapidjson::StringBuffer body;
    rapidjson::Writer<rapidjson::StringBuffer> bw(body);
    d["body"].Accept(bw);
    std::string response;
    try {
      response = engine->route(body.GetString());
    } catch (const valhalla::valhalla_exception_t& e) {
      response = error_json(static_cast<int64_t>(e.code), e.message);
    } catch (const std::exception& e) {
      response = error_json(-1, e.what());
    } catch (...) {
      response = error_json(-1, "unknown exception");
    }
    std::cout << response_line(id, "response", response) << '\n' << std::flush;
  }
  return 0;
}
