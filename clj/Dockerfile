# ARK Discord Bot - Babashka/Clojure
FROM babashka/babashka:latest

# Create non-root user (Ubuntu-based image uses groupadd/useradd)
RUN groupadd --gid 10000 appgroup && \
    useradd --uid 10000 --gid 10000 --no-create-home --shell /bin/false appuser

WORKDIR /app

# Copy source files (deps.edn is for Clojure CLI, not needed for babashka)
COPY bb.edn ./
COPY src/ ./src/

# Set ownership
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD bb -e "(println :ok)" || exit 1

# Run the bot
CMD ["bb", "start"]
