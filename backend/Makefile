# NAV-001 local stack. Run from backend/. `make help` lists targets.
COMPOSE  ?= docker compose
BASE_URL ?= http://localhost:$(or $(GATEWAY_PORT),8080)
PYTHON   ?= python3
QA_SMOKE := ../tests/smoke/run.sh
SPEC     := ../docs/architecture/api/openapi.yaml
CONTRACT_ARGS ?=

# NAV-008 guard: on the staging host (infra/staging/.env exists) the service-changing targets below would use the
# dev .env (CORS *, dev OSM mirror, no rate limits) under the same project name `navmn`, stop or recreate the
# staging gateway off the edge network (Caddy -> 502) and delete the live data. Staging uses infra/staging/bin/*
# (RUNBOOK.md). Read-only targets (help, ps, logs, smoke, perf, contract, stats, build-info, tiles-info) still work.
STAGING_ENV_FILE ?= ../infra/staging/.env
STAGING_REFUSED  := .env up down restart cors-check rebuild-data clean-data nav006-up nav006-test-setup nav006-test-teardown \
                    nav020-test-setup nav020-test-teardown pack-export-test
ifneq ($(wildcard $(STAGING_ENV_FILE)),)
ifneq ($(filter $(STAGING_REFUSED),$(MAKECMDGOALS)),)
ifneq ($(NAV_DEV_TARGETS_ON_STAGING),yes)
$(error refusing '$(filter $(STAGING_REFUSED),$(MAKECMDGOALS))': $(STAGING_ENV_FILE) exists, so this is the staging host. \
Use infra/staging/bin/deploy.sh, nav-rebuild.sh --force or nav-compose (RUNBOOK.md sections 6-7). \
Override only on a dev machine that has a stray staging .env: NAV_DEV_TARGETS_ON_STAGING=yes)
endif
endif
endif

.DEFAULT_GOAL := help
.PHONY: help up down restart ps logs smoke smoke-backend perf contract rate-limit-check cors-check rebuild-data \
        clean-data stats tiles-info build-info rebuild rollback status slot-checksums nav006-up nav006-ps \
        nav006-test-setup nav006-test-teardown pipeline-test pack-publish pack-status pack-test gate2-image \
        nav020-test-setup nav020-test-teardown

help: ## Show this help
	@awk 'BEGIN{FS=":.*## "} /^[a-zA-Z0-9_-]+:.*## /{printf "  %-14s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

.env:
	cp .env.example .env
	@echo "created .env from .env.example"

up: .env ## Build data if needed and start everything; waits until all services are healthy
	$(COMPOSE) up -d --wait
	@$(COMPOSE) ps --format 'table {{.Service}}\t{{.Status}}'

down: ## Stop and remove containers (data/ is kept)
	$(COMPOSE) down

restart: down up ## down + up (reuses data/)

ps: ## Service status
	$(COMPOSE) ps -a --format 'table {{.Service}}\t{{.State}}\t{{.Status}}'

logs: ## Follow logs of all services
	$(COMPOSE) logs -f --tail=100

smoke: ## Smoke test through the gateway (QA's tests/smoke/run.sh if present, else the backend suite)
	@if [ -x $(QA_SMOKE) ]; then BASE_URL=$(BASE_URL) $(QA_SMOKE); \
	else $(PYTHON) scripts/smoke.py --base-url $(BASE_URL); fi

smoke-backend: ## Backend's own smoke suite (stdlib Python, gateway only)
	$(PYTHON) scripts/smoke.py --base-url $(BASE_URL)

perf: ## Smoke + AC 35-38 latency baselines (p95 of 20 sequential requests)
	$(PYTHON) scripts/smoke.py --base-url $(BASE_URL) --perf

.venv/bin/python: scripts/requirements-dev.txt
	$(PYTHON) -m venv .venv
	.venv/bin/pip install -q -r scripts/requirements-dev.txt
	@touch .venv/bin/python

contract: .venv/bin/python ## Validate live responses against docs/architecture/api/openapi.yaml (CONTRACT_ARGS=--rate-limit on staging)
	.venv/bin/python scripts/contract_check.py --base-url $(BASE_URL) --spec $(SPEC) $(CONTRACT_ARGS)

rate-limit-check: ## NAV-008 AC 13 probe (100 r/s bursts); only against a gateway with GATEWAY_RATE_LIMIT=on, never the shared dev stack
	@case "$(BASE_URL)" in *localhost:8080*|*127.0.0.1:8080*) echo "refusing: BASE_URL is the shared dev gateway (rate limits off there)"; exit 2;; esac
	$(PYTHON) scripts/rate_limit_check.py --base-url $(BASE_URL)

cors-check: ## AC 31: restart the gateway with an allowlist, check it, restore .env setting
	CORS_ALLOWED_ORIGINS=http://localhost:5173 $(COMPOSE) up -d --no-deps --wait gateway
	$(PYTHON) scripts/smoke.py --base-url $(BASE_URL) --cors-allowlist http://localhost:5173; rc=$$?; \
	$(COMPOSE) up -d --no-deps --wait gateway; exit $$rc

rebuild-data: .env ## Force rebuild of tiles, graph and index from the current .env sources (keeps cached aux files)
	$(COMPOSE) stop gateway valhalla photon
	$(COMPOSE) run --rm --no-deps data-fetch clean
	$(COMPOSE) up -d --wait --force-recreate
	@$(COMPOSE) ps --format 'table {{.Service}}\t{{.Status}}'

clean-data: ## Delete ALL of data/ including the ~2.5 GB of cached downloads (asks first)
	@read -p "Delete backend/data entirely? [y/N] " a && [ "$$a" = y ]
	$(COMPOSE) down
	docker run --rm -v "$(CURDIR)/data:/data" --entrypoint sh $$($(COMPOSE) config --images | grep valhalla | head -1) -c 'rm -rf /data/* /data/.[!.]*'

stats: ## Memory/CPU of running services and size of data/ (AC 39)
	docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}' $$($(COMPOSE) ps -q)
	@du -sh data 2>/dev/null; du -sh data/* 2>/dev/null || true

build-info: ## Print data/build-info.json (sources, dates, versions)
	@cat data/build-info.json

tiles-info: ## pmtiles show on the built archive (go-pmtiles image)
	docker run --rm -v "$(CURDIR)/data/tiles:/t:ro" $${PMTILES_IMAGE:-ghcr.io/protomaps/go-pmtiles:v1.31.2} show /t/basemap.pmtiles

# ---------------------------------------------------------------- NAV-006 daily rebuild pipeline (ADR-0014)
# Config file: staging = ../infra/staging/.env (automatic); dev-container test = $(NAV006_TEST_ROOT)/nav006.env
# (make nav006-test-setup). The dev .env is never used: it has no NAV_DATA_ROOT and its project is navmn.
# make passes the pipeline's exit code on as "Error <code>" (make itself exits 2); `make status` shows it too.
NAV006_TEST_ROOT ?= /var/tmp/nav006-test
NAV020_TEST_ENV = $(wildcard $(or $(NAV020_TEST_ROOT),/var/tmp/nav020-test)/nav020.env)
NAV_ENV_FILE ?= $(if $(wildcard $(STAGING_ENV_FILE)),$(STAGING_ENV_FILE),$(or $(wildcard $(NAV006_TEST_ROOT)/nav006.env),$(NAV020_TEST_ENV)))
PIPELINE = $(if $(wildcard $(STAGING_ENV_FILE)),../infra/staging/bin/nav-pipeline,$(PYTHON) pipeline/nav_pipeline.py --env-file $(NAV_ENV_FILE))
SLOTS_COMPOSE = $(COMPOSE) --project-directory . -f compose.slots.yaml --env-file $(NAV_ENV_FILE)
REBUILD_FLAGS = $(if $(filter 1,$(FORCE)),--force) $(if $(filter 1,$(ACCEPT_SIZE_DROP)),--accept-size-drop) \
                $(if $(filter 1,$(ACCEPT_ROUTE_CHANGE)),--accept-route-change) $(if $(filter 1,$(REFRESH_AUX)),--refresh-aux)

nav006-env-check:
	@test -n "$(NAV_ENV_FILE)" || { echo "no pipeline config: set NAV_ENV_FILE=<file> (staging: infra/staging/.env; test: make nav006-test-setup)"; exit 2; }

rebuild: nav006-env-check ## NAV-006: fetch, validate, build a new slot, verify, switch (FORCE=1 ACCEPT_SIZE_DROP=1 ACCEPT_ROUTE_CHANGE=1 REFRESH_AUX=1)
	$(PIPELINE) rebuild $(REBUILD_FLAGS)

rollback: nav006-env-check ## NAV-006: serve the previous slot again (one step)
	$(PIPELINE) rollback

status: nav006-env-check ## NAV-006: JSON status (active/previous slot, data dates, last run, stale)
	@$(PIPELINE) status

slot-checksums: nav006-env-check ## NAV-006 AC 7: sha256 listing of slot SLOT=<id> (Photon runtime state excluded)
	@$(PIPELINE) checksums $(SLOT)

nav006-test-setup: ## NAV-006 dev-container test root (hard links + copies of data/, never changes it)
	pipeline/test-setup.sh $(NAV006_TEST_ROOT)

nav006-up: nav006-env-check ## NAV-006 test: start the test project's public gateway (port from NAV_ENV_FILE)
	$(SLOTS_COMPOSE) up -d --wait gateway

nav006-ps: nav006-env-check ## NAV-006 test: containers of the test project
	$(SLOTS_COMPOSE) ps -a --format 'table {{.Service}}\t{{.State}}\t{{.Status}}'

nav006-test-teardown: ## NAV-006 test: remove the test project (containers, volumes, networks) and its root
	pipeline/test-teardown.sh $(NAV006_TEST_ROOT)

pipeline-test: ## NAV-006 + NAV-020 pipeline unit tests (no Docker, a few seconds)
	$(PYTHON) -m unittest discover -s pipeline/tests -v

# ---------------------------------------------------------------- NAV-020 offline pack (ADR-0017; RUNBOOK NAV-020)
# Config: the same NAV_ENV_FILE as rebuild (staging .env automatic). Test project: make nav020-test-setup, then
# NAV_ENV_FILE=$(NAV020_TEST_ROOT)/nav020.env. The timer never passes FORCE or TILES.
NAV020_TEST_ROOT ?= /var/tmp/nav020-test
GATE2_IMAGE ?= navmn-gate2:0.6.3
GATE2_VARIANT ?= wrapper
PACK_FLAGS = $(if $(filter 1,$(FORCE)),--force) $(if $(filter 1,$(TILES)),--tiles)

pack-publish: nav006-env-check ## NAV-020: pack step on the active slot, same lock as rebuild (FORCE=1 weekly part, TILES=1 also tiles)
	$(PIPELINE) pack-publish $(PACK_FLAGS)

pack-status: nav006-env-check ## NAV-020: the pack part of make status (published versions, last result, Gate 2 engine)
	@$(PIPELINE) pack-status

pack-test: ## NAV-020/NAV-022 unit tests: pack step, static export, search DB builder (no Docker, a few seconds)
	$(PYTHON) -m unittest discover -s pipeline/tests -p 'test_nav_pack*.py' -v
	$(PYTHON) -m unittest discover -s pipeline/tests -p 'test_pack_export*.py' -v
	$(PYTHON) -m unittest discover -s pack/tests -v

gate2-image: ## NAV-020 Gate 2 engine image (>= 20 GB free, 45-90 min; GATE2_VARIANT=upstream = ADR-0017 A1 item 2)
	@avail=$$(df -Pk /var/lib/docker 2>/dev/null | awk 'NR==2{print int($$4/1048576)}'); \
	if [ "$${avail:-0}" -lt 20 ]; then echo "refusing: $${avail:-?} GB free under /var/lib/docker; the Gate 2 build needs >= 20 GB (RUNBOOK NAV-020)"; exit 2; fi
	docker build --build-arg GATE2_VARIANT=$(GATE2_VARIANT) -t $(GATE2_IMAGE) gate2
	docker image inspect $(GATE2_IMAGE) --format '{{.Id}} {{json .Config.Labels}}'

nav020-test-setup: ## NAV-020 dev-container test root (separate project navmn-nav020, gateway 127.0.0.1:18190)
	pipeline/nav020-test-setup.sh $(NAV020_TEST_ROOT)

nav020-test-teardown: ## NAV-020 test: remove the test project (containers, volumes, networks) and its root
	pipeline/nav020-test-teardown.sh $(NAV020_TEST_ROOT)

# ---------------------------------------------------------------- NAV-022 static pack hosting (README "Static pack hosting")
# Source: PACKS_DIR=<NAV_DATA_ROOT>/packs, else the packs directory of NAV_ENV_FILE (staging .env automatic).
# pack-export (reads the packs, writes only DEST) and pack-static-check (HTTP GETs) are allowed on staging.
# The pack base URL of a real host is never committed: pass it on the command line only.
PACK_EXPORT_FLAGS = $(if $(filter 0,$(HTACCESS)),--no-htaccess) $(if $(filter 1,$(LINK)),--link) $(if $(filter 1,$(QUICK)),--quick)

pack-export: ## NAV-022: latest published pack -> static tree DEST=<dir> for a static host (PACKS_DIR=, HTACCESS=0, LINK=1)
	@test -n "$(DEST)" || { echo "usage: make pack-export DEST=<dir> [PACKS_DIR=<NAV_DATA_ROOT>/packs | NAV_ENV_FILE=<file>] [HTACCESS=0] [LINK=1] [QUICK=1]"; exit 2; }
	@test -n "$(PACKS_DIR)$(NAV_ENV_FILE)" || { echo "no pack source: set PACKS_DIR=<NAV_DATA_ROOT>/packs or NAV_ENV_FILE=<file>"; exit 2; }
	$(PYTHON) pipeline/pack_export.py export --dest "$(DEST)" $(if $(PACKS_DIR),--packs-dir "$(PACKS_DIR)",--env-file "$(NAV_ENV_FILE)") $(PACK_EXPORT_FLAGS)

pack-static-check: ## NAV-022: check a static host serving an export (PACK_BASE_URL=https://.../packs/ [PACK_LOCAL=<DEST>] [FULL=1])
	@test -n "$(PACK_BASE_URL)" || { echo "usage: make pack-static-check PACK_BASE_URL=https://<host>/<path>/packs/ [PACK_LOCAL=<export dir>] [FULL=1]"; exit 2; }
	$(PYTHON) scripts/pack_static_check.py --base-url "$(PACK_BASE_URL)" $(if $(PACK_LOCAL),--local "$(PACK_LOCAL)") $(if $(filter 1,$(FULL)),--full)

pack-export-test: ## NAV-022: export + .htaccess on Apache httpd with shared-host defaults (project navmn-nav022, 127.0.0.1:18191; ~110 MB image, removed after)
	pipeline/nav022-static-test.sh $(NAV022_TEST_ROOT)
