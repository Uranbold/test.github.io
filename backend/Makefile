# NAV-001 local stack. Run from backend/. `make help` lists targets.
COMPOSE  ?= docker compose
BASE_URL ?= http://localhost:$(or $(GATEWAY_PORT),8080)
PYTHON   ?= python3
QA_SMOKE := ../tests/smoke/run.sh
SPEC     := ../docs/architecture/api/openapi.yaml

.DEFAULT_GOAL := help
.PHONY: help up down restart ps logs smoke smoke-backend perf contract cors-check rebuild-data \
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

contract: .venv/bin/python ## Validate live responses against docs/architecture/api/openapi.yaml
	.venv/bin/python scripts/contract_check.py --base-url $(BASE_URL) --spec $(SPEC)

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
