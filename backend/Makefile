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
STAGING_REFUSED  := .env up down restart cors-check rebuild-data clean-data
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
        clean-data stats tiles-info build-info

help: ## Show this help
	@awk 'BEGIN{FS=":.*## "} /^[a-zA-Z_-]+:.*## /{printf "  %-14s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

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
