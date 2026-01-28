.PHONY: test lint format format-check ci run uberjar clean docker-build docker-run native-config native-config-test native-config-run docker-native-config-build docker-native-config-test docker-native-config-run

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

# Native image configuration directory
NATIVE_CONFIG_DIR := resources/META-INF/native-image/ark-discord-bot

# Generate native-image reflection config by running tests with tracing agent
# This captures reflection usage during test execution
native-config-test:
	clojure -J-agentlib:native-image-agent=config-merge-dir=$(NATIVE_CONFIG_DIR)/ -M:test

# Generate native-image reflection config by running the app with tracing agent
# Requires .env file with valid credentials. Run various commands (!ark status, etc.) then Ctrl+C
native-config-run:
	clojure -J-agentlib:native-image-agent=config-merge-dir=$(NATIVE_CONFIG_DIR)/ -M:run

# Generate native-image config (runs tests to capture reflection usage)
# Use 'make native-config-run' for additional coverage with real RCON connection
native-config: native-config-test
	@echo "Native image config updated in $(NATIVE_CONFIG_DIR)/"
	@echo "Review the generated files and commit if appropriate."
	@echo "For better coverage, also run 'make native-config-run' with valid .env"

# Docker image for GraalVM tracing agent (with Clojure pre-installed)
GRAALVM_AGENT_IMAGE := ark-discord-bot-graalvm-agent:latest

# Build the GraalVM agent Docker image (run once, or when Dockerfile.graalvm-agent changes)
docker-native-config-build:
	docker build -t $(GRAALVM_AGENT_IMAGE) -f Dockerfile.graalvm-agent .

# Generate native-image config using Docker (no local GraalVM required)
# Runs tests inside GraalVM container with tracing agent
# Run 'make docker-native-config-build' first if the image doesn't exist
docker-native-config-test:
	docker run --rm \
		-v "$(CURDIR)":/app \
		-v "$(HOME)/.m2":/root/.m2 \
		-v "$(HOME)/.gitlibs":/root/.gitlibs \
		-w /app \
		$(GRAALVM_AGENT_IMAGE) \
		bash -c "clojure -J-agentlib:native-image-agent=config-merge-dir=$(NATIVE_CONFIG_DIR)/ -M:test && \
			chown -R $(shell id -u):$(shell id -g) $(NATIVE_CONFIG_DIR)/"

# Generate native-image config by running the app in Docker with tracing agent
# Requires .env file with valid credentials
# Run 'make docker-native-config-build' first if the image doesn't exist
docker-native-config-run:
	docker run --rm -it \
		-v "$(CURDIR)":/app \
		-v "$(HOME)/.m2":/root/.m2 \
		-v "$(HOME)/.gitlibs":/root/.gitlibs \
		--env-file .env \
		-w /app \
		$(GRAALVM_AGENT_IMAGE) \
		bash -c "clojure -J-agentlib:native-image-agent=config-merge-dir=$(NATIVE_CONFIG_DIR)/ -M:run && \
			chown -R $(shell id -u):$(shell id -g) $(NATIVE_CONFIG_DIR)/"
