.PHONY: test lint format format-check ci run docker-build docker-run clean

# Default target
all: ci

# Run all tests
test:
	bb test

# Run linter
lint:
	bb lint

# Check code formatting
format-check:
	bb format:check

# Fix code formatting
format:
	bb format:fix

# Run all CI checks
ci: format-check lint test

# Run the bot
run:
	bb run

# Docker build
docker-build:
	bb docker:build

# Docker run
docker-run:
	docker run --env-file ../.env ark-discord-bot-clj:latest

# Clean generated files
clean:
	rm -rf .cpcache .clj-kondo/.cache target
