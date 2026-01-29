# Stage 1: Build uberjar
FROM clojure:temurin-21-tools-deps-alpine@sha256:93480cf9e020ab0bb9416f54d60be0aa06aa4dc7dbb6dc8bff21ea8a837846ae AS builder

WORKDIR /build

# Copy dependency files first for better caching
COPY deps.edn build.clj ./

# Download dependencies
RUN clojure -P && clojure -A:build -P

# Copy source and resources
COPY src/ ./src/
COPY resources/ ./resources/

# Build uberjar with AOT compilation
RUN clojure -T:build uberjar

# Stage 2: Build native image
FROM ghcr.io/graalvm/native-image:21@sha256:5689c19b344f146844d02da97723d0ac21f84884db0dd156c37f999758b3fb3f AS native-builder

WORKDIR /build

# Copy the uberjar from builder stage
COPY --from=builder /build/target/*-standalone.jar /build/app.jar

# Build native image with Clojure-optimized settings
RUN native-image \
    --initialize-at-build-time \
    --no-fallback \
    --report-unsupported-elements-at-runtime \
    -H:+ReportExceptionStackTraces \
    -H:IncludeResources=config.edn \
    --enable-http \
    --enable-https \
    -jar /build/app.jar \
    ark-discord-bot

# Stage 3: Runtime (minimal)
FROM debian:bookworm-slim@sha256:56ff6d36d4eb3db13a741b342ec466f121480b5edded42e4b7ee850ce7a418ee

# Install minimal runtime dependencies
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ca-certificates \
        libstdc++6 \
        zlib1g && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --gid 10000 appgroup && \
    useradd --uid 10000 --gid 10000 --no-create-home --shell /bin/false appuser

WORKDIR /app

# Copy native binary from native-builder stage
COPY --from=native-builder /build/ark-discord-bot /app/ark-discord-bot

# Set ownership
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD test -f /app/ark-discord-bot || exit 1

# Run the bot
CMD ["/app/ark-discord-bot"]
