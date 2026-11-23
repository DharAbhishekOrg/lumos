# Lumos - backend/deploy/staging / The lumosd image: a static binary on scratch, non-root, plus the CA bundle.
# Author: dhar.abhishek.email@gmail.com
# Implements: TRS-BCK-001, TRS-BCK-002, TRS-REP-005
# ADRs: ADR-001
# Notes: WP-05 S8. The same image runs in the local docker-compose staging,
# in CI's staging job and, when the owner deploys it, on Cloud Run. The
# builder is pinned by digest, so a rebuild is the same build and the CA
# bundle it supplies is pinned with it. The binary is static (CGO off),
# trimmed of paths, and runs as uid/gid 65532 on an EMPTY base: no shell, no
# package manager, nothing to run after a compromise. Configuration is only
# environment variables (LUMOS_DATABASE_URL, LUMOS_REDIS_URL, push variables);
# no secret is ever baked into the image (TRS-REP-005). Build from the
# repository root: docker build -f backend/deploy/staging/Dockerfile .
#
# What the image contains, and the owner's licence ruling (2026-11-23):
#   /lumosd                              Lumos (Go stdlib BSD-3-Clause; Go modules per go.mod)
#   /etc/ssl/certs/ca-certificates.crt   Mozilla CA certificate data, unmodified, from Alpine's
#                                        ca-certificates-bundle (MPL-2.0 AND MIT). Needed for TLS
#                                        to FCM and APNs. MPL-2.0 is weak copyleft; the owner
#                                        approved this one unmodified data file explicitly.
#   /usr/share/licenses/ca-certificates/NOTICE   where the data comes from
# The golang builder (BSD-3-Clause) is build-only and not shipped. A Google
# distroless base was considered and declined: it carries Debian packages
# (netbase GPL-2 data, ca-certificates GPL-2+ scripts) the binary never needs.

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

FROM scratch
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY backend/deploy/staging/NOTICE-ca-certificates.txt /usr/share/licenses/ca-certificates/NOTICE
COPY --from=build /out/lumosd /lumosd
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/lumosd", "-addr", "0.0.0.0:8080"]
