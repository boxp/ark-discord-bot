.PHONY: test lint format format-check ci run uberjar clean docker-build docker-run

# Default target
all: ci

# Run all tests
test:
	clojure -M:test

# Run linter
lint:
	clojure -M:lint

# Check code formatting
format-check:
	clojure -M:format-check

# Fix code formatting
format:
	clojure -M:format-fix

# Run all CI checks
ci: format-check lint test

# Run the bot
run:
	clojure -M:run

# Build uberjar
uberjar:
	clojure -T:build uberjar

# Clean generated files
clean:
	clojure -T:build clean
	rm -rf .cpcache .clj-kondo/.cache

# Docker build
docker-build:
	docker build -t ark-discord-bot:latest .

# Docker run
docker-run:
	docker run --env-file .env ark-discord-bot:latest
