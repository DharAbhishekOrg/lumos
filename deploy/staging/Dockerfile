# Lumos - deploy/staging / The lumosd image: a static binary on distroless, non-root.
# Author: dhar.abhishek.email@gmail.com
# Implements: TRS-BCK-001, TRS-BCK-002, TRS-REP-005
# ADRs: ADR-001
# Notes: WP-05 S8. The same image runs in the local docker-compose staging,
# in CI's staging job and, when the owner deploys it, on Cloud Run. Base
# images are pinned by digest so a rebuild is the same build. The binary is
# static (CGO off), trimmed of paths, and runs as distroless's nonroot user
# with no shell, so a compromise has nothing to run. Configuration is only
# environment variables (LUMOS_DATABASE_URL, LUMOS_REDIS_URL, push variables);
# no secret is ever baked into the image (TRS-REP-005). Build from the
# repository root: docker build -f deploy/staging/Dockerfile .
#
# Licences: golang (BSD-3-Clause) is build-only and not shipped; the runtime
# base gcr.io/distroless/static-debian12 is Apache-2.0 with Debian's
# ca-certificates and tzdata (MPL-2.0 / public domain), unmodified.

FROM golang:1.26-alpine@sha256:ecb67f987aa1a57794c91d19ade7ae0209f2f0f5496aaef9e6ae85ddf92d02ab AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY backend ./backend
COPY protocol ./protocol
# Provenance (TRS-REP-007): the health check reports what was built, from what.
ARG REVISION=unknown
ARG BUILT_AT=unset
ARG BUILD_ENV=staging
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath \
    -ldflags="-s -w -X main.revision=${REVISION} -X main.buildTime=${BUILT_AT} -X main.buildEnv=${BUILD_ENV}" \
    -o /out/lumosd ./backend/cmd/lumosd

FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab
COPY --from=build /out/lumosd /lumosd
USER nonroot:nonroot
EXPOSE 8080
ENTRYPOINT ["/lumosd", "-addr", "0.0.0.0:8080"]
