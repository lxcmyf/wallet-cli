# CLAUDE.md — wallet-cli

## Project Overview

TRON Wallet Command Line Interface (wallet-cli). Java 1.8 project built with Gradle.
Supports key management, transaction signing, Ledger hardware wallet, GasFree, and MCP server.

## Build & Test

```bash
./gradlew build        # Build the project
./gradlew test         # Run tests
./gradlew shadowJar    # Build fat jar
```

## Security Review Integration

This project uses `claude-code-security-review` — a multi-layer security scanning setup:

### Layers

| Layer | Trigger | Tool | Config |
|-------|---------|------|--------|
| Pre-commit hook | `git commit` | semgrep + pattern check | `scripts/pre-commit-security-check.sh` |
| Claude Code hook | Claude Code Stop event | semgrep on changed `.java` files | `.claude/settings.local.json` |
| CI (GitHub Actions) | PR / push to develop/master | semgrep (auto + OWASP + Java) | `.github/workflows/security-review.yml` |
| Manual audit | `/code-rw` in Claude Code | semgrep + Claude deep analysis + Excel report | `.claude/skills/code-rw/SKILL.md` |

### Setup Pre-commit Hook

```bash
ln -sf ../../scripts/pre-commit-security-check.sh .git/hooks/pre-commit
```

### Run Manual Audit

In Claude Code, type `/code-rw` to run a full security audit with Excel report generation.

## Security Coding Guidelines

### Banned Patterns (CI will block)

- `MessageDigest.getInstance("MD5")` — use SHA-256 or stronger
- `DES/` encryption — use AES-256
- Hardcoded passwords in source code

### Warned Patterns (CI will warn, should fix)

- `AES/ECB` mode — use `AES/GCM/NoPadding` or `AES/CBC/PKCS5Padding`
- `ex.printStackTrace()` — use `log.error("message", ex)` with `@Slf4j`
- Custom `MessageDigest` implementations — prefer standard library when possible

### Crypto Rules

- Always use `SecureRandom` for random number generation (already used correctly)
- Use `AES/GCM/NoPadding` for symmetric encryption with random IV
- Never store private keys or mnemonics in plaintext
- Use scrypt/PBKDF2 for key derivation (already used via keystore)

### Input Validation

- Validate Base58Check addresses before use (`WalletApi.decodeFromBase58Check`)
- Validate amounts are positive and within range before transaction creation
- Use `ProcessBuilder` with argument lists (not string concatenation) for external commands

## Code Style

- Logging: use `@Slf4j` (Lombok) — avoid `System.out.println` and `printStackTrace`
- Exceptions: catch specific types, log with context, never swallow silently
- Crypto: use `javax.crypto` / BouncyCastle — no custom implementations for standard algorithms
